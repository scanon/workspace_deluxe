package us.kbase.workspace.workspaces;

import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import us.kbase.typedobj.core.AbsoluteTypeId;
import us.kbase.typedobj.core.TypeId;
import us.kbase.workspace.database.WorkspaceObjectID;

public class WorkspaceSaveObject {
	
	private static final ObjectMapper mapper = new ObjectMapper();
	private static final int MAX_USER_META_SIZE = 16000;
	
	private final WorkspaceObjectID id;
	private final JsonNode data;
	private final TypeId type;
	private final Map<String, String> userMeta;
	private final Provenance provenance;
	private final boolean hidden;
	
	public WorkspaceSaveObject(final WorkspaceObjectID id, final Object data,
			final TypeId type, final Map<String, String> userMeta,
			final Provenance provenance, final boolean hidden) {
		if (id == null || data == null || type == null) {
			throw new IllegalArgumentException("Neither id, data nor type may be null");
		}
		this.id = id;
		try {
			this.data = mapper.valueToTree(data);
		} catch (IllegalArgumentException iae) {
			throw new IllegalArgumentException("Cannot serialize data", iae);
		}
		this.type = type;
		this.userMeta = userMeta;
		this.provenance = provenance;
		this.hidden = hidden;
		checkMeta(userMeta);
	}
	
	public WorkspaceSaveObject(final WorkspaceObjectID id, final JsonNode data,
			final TypeId type, final Map<String, String> userMeta,
			final Provenance provenance, final boolean hidden) {
		if (id == null || data == null || type == null) {
			throw new IllegalArgumentException("Neither id, data nor type may be null");
		}
		this.id = id;
		this.data = data;
		this.type = type;
		this.userMeta = userMeta;
		this.provenance = provenance;
		this.hidden = hidden;
		checkMeta(userMeta);
	}
	
	public WorkspaceSaveObject(final Object data, final TypeId type,
			final Map<String, String> userMeta,  final Provenance provenance,
			final boolean hidden) {
		if (data == null || type == null) {
			throw new IllegalArgumentException("Neither data nor type may be null");
		}
		this.id = null;
		try {
			this.data = mapper.valueToTree(data);
		} catch (IllegalArgumentException iae) {
			throw new IllegalArgumentException("Cannot serialize data", iae);
		}
		this.type = type;
		this.userMeta = userMeta;
		this.provenance = provenance;
		this.hidden = hidden;
		checkMeta(userMeta);
	}
	
	public WorkspaceSaveObject(final JsonNode data, final TypeId type,
			final Map<String, String> userMeta, final Provenance provenance,
			final boolean hidden) {
		if (data == null || type == null) {
			throw new IllegalArgumentException("Neither data nor type may be null");
		}
		this.id = null;
		this.data = data;
		this.type = type;
		this.userMeta = userMeta;
		this.provenance = provenance;
		this.hidden = hidden;
		checkMeta(userMeta);
	}
	
	private final static String META_ERR = String.format(
			"Metadata is > %s bytes", MAX_USER_META_SIZE);
	
	private void checkMeta(final Map<String, String> meta) {
		if (meta != null) {
			final String jsonmeta;
			try {
				jsonmeta = mapper.writeValueAsString(meta);
			} catch (JsonProcessingException jpe) {
				throw new IllegalArgumentException(
						"Unable to serialize metadata", jpe);
			}
			if (jsonmeta.length() > MAX_USER_META_SIZE) {
				throw new IllegalArgumentException(META_ERR);
			}
		}
	}

	public WorkspaceObjectID getObjectIdentifier() {
		return id;
	}

	//mutable!
	public JsonNode getData() {
		return data;
	}

	public TypeId getType() {
		return type;
	}

	//mutable!
	public Map<String, String> getUserMeta() {
		return userMeta;
	}

	public Provenance getProvenance() {
		return provenance;
	}

	public boolean isHidden() {
		return hidden;
	}

	public ResolvedSaveObject resolve(final AbsoluteTypeId type,
			final JsonNode resolvedData) {
		if (id == null) {
			return new ResolvedSaveObject(resolvedData, type, this.userMeta,
					this.provenance, this.hidden);
		} else {
			return new ResolvedSaveObject(this.id, resolvedData, type,
					this.userMeta, this.provenance, this.hidden);
		}
	}
	
	@Override
	public String toString() {
		return "WorkspaceSaveObject [id=" + id + ", data=" + data + ", type="
				+ type + ", userMeta=" + userMeta + ", provenance="
				+ provenance + ", hidden=" + hidden + "]";
	}
}
