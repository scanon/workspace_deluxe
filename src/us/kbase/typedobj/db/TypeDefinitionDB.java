package us.kbase.typedobj.db;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.commons.codec.digest.DigestUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.fge.jsonschema.cfg.ValidationConfiguration;
import com.github.fge.jsonschema.main.JsonSchema;
import com.github.fge.jsonschema.main.JsonSchemaFactory;

import us.kbase.jkidl.StaticIncludeProvider;
import us.kbase.kidl.KbFuncdef;
import us.kbase.kidl.KbList;
import us.kbase.kidl.KbMapping;
import us.kbase.kidl.KbModule;
import us.kbase.kidl.KbModuleComp;
import us.kbase.kidl.KbParameter;
import us.kbase.kidl.KbScalar;
import us.kbase.kidl.KbService;
import us.kbase.kidl.KbStruct;
import us.kbase.kidl.KbStructItem;
import us.kbase.kidl.KbTuple;
import us.kbase.kidl.KbType;
import us.kbase.kidl.KbTypedef;
import us.kbase.kidl.KbUnspecifiedObject;
import us.kbase.kidl.KidlParser;
import us.kbase.kidl.tests.KidlTest;
import us.kbase.typedobj.core.AbsoluteTypeDefId;
import us.kbase.typedobj.core.MD5;
import us.kbase.typedobj.core.TypeDefId;
import us.kbase.typedobj.core.TypeDefName;
import us.kbase.typedobj.core.validatorconfig.ValidationConfigurationFactory;
import us.kbase.typedobj.exceptions.*;

/**
 * This class is the primary interface for storing and retrieving versioned typed
 * object definitions and association meta information.
 * 
 * @author msneddon
 * @author rsutormin
 *
 */
public class TypeDefinitionDB {

	public enum KidlSource {
		external, internal, both
	}
	
	/**
	 * This is the factory used to create a JsonSchema object from a Json Schema
	 * document stored in the DB.
	 */
	protected JsonSchemaFactory jsonSchemaFactory; 
	
	/**
	 * The Jackson ObjectMapper which can translate a raw Json Schema document to a JsonTree
	 */
	protected ObjectMapper mapper;
	
	private static final SemanticVersion defaultVersion = new SemanticVersion(0, 1);
	private static final SemanticVersion releaseVersion = new SemanticVersion(1, 0);
	private static final long maxDeadLockWaitTime = 120000;
	
	private final TypeStorage storage;
	private final File parentTempDir;
	private final UserInfoProvider uip;
	private final Object tempDirLock = new Object(); 
	private final Object moduleStateLock = new Object(); 
	private final Map<String, ModuleState> moduleStates = new HashMap<String, ModuleState>();
	private final ThreadLocal<Map<String,Integer>> localReadLocks = new ThreadLocal<Map<String,Integer>>(); 
	private final String kbTopPath;
	private final KidlSource kidlSource;
	
	enum Change {
		noChange, backwardCompatible, notCompatible;
		
		public static Change joinChanges(Change c1, Change c2) {
			return Change.values()[Math.max(c1.ordinal(), c2.ordinal())];
		}
	}
	

	/**
	 * Set up a new DB pointing to the specified storage object
	 * @param storage
	 * @param uip
	 * @throws TypeStorageException 
	 */
	public TypeDefinitionDB(TypeStorage storage, UserInfoProvider uip)
			throws TypeStorageException {
		this(storage, null, uip);
	}

	public TypeDefinitionDB(TypeStorage storage, File tempDir,
			UserInfoProvider uip) throws TypeStorageException {
		this(storage, tempDir, uip, null, null);
	}
	
	/**
	 * Setup a new DB handle pointing to the specified storage object, using the
	 * specified location when processing temporary type compiler files.
	 * @param storage
	 * @param tempDir
	 * @param uip
	 * @param kbTopPath
	 * @param kidlSource
	 * @throws TypeStorageException 
	 */
	public TypeDefinitionDB(TypeStorage storage, File tempDir,
			UserInfoProvider uip, String kbTopPath, String kidlSource) throws TypeStorageException {
		this.mapper = new ObjectMapper();
		// Create the custom json schema factory for KBase typed objects and use this
		ValidationConfiguration kbcfg = ValidationConfigurationFactory.buildKBaseWorkspaceConfiguration();
		this.jsonSchemaFactory = JsonSchemaFactory.newBuilder()
									.setValidationConfiguration(kbcfg)
									.freeze();
		this.storage = storage;
		if (tempDir == null) {
			this.parentTempDir = new File(".");
		} else {
			this.parentTempDir = tempDir;
			if (parentTempDir.exists()) {
				if (!parentTempDir.isDirectory()) {
					throw new TypeStorageException("Requested temp dir "
							+ parentTempDir + " is not a directory");
				}
			} else {
				boolean success = parentTempDir.mkdirs();
				if (!success) {
					if (!parentTempDir.isDirectory()) {
						throw new TypeStorageException(
								"Could not create requested temp dir "
						+ parentTempDir);
					}
				}
			}
		}
		this.uip = uip;
		this.kbTopPath = kbTopPath;
		this.kidlSource = kidlSource == null || kidlSource.isEmpty() ? KidlSource.internal : 
			KidlSource.valueOf(kidlSource);
	}
	
	
	/**
	 * Retrieve a Json Schema Document for the most recent version of the typed object specified
	 * @param typeDefName
	 * @return
	 * @throws NoSuchTypeException
	 * @throws NoSuchModuleException
	 * @throws TypeStorageException
	 */
	public String getJsonSchemaDocument(final TypeDefName typeDefName) 
			throws NoSuchTypeException, NoSuchModuleException, TypeStorageException {
		return getJsonSchemaDocument(new TypeDefId(typeDefName));
	}
	
	private ModuleState getModuleState(String moduleName) {
		synchronized (moduleStateLock) {
			ModuleState ret = moduleStates.get(moduleName);
			if (ret == null) {
				ret = new ModuleState();
				moduleStates.put(moduleName, ret);
			}
			return ret;
		}
	}
	
	private int getLocalReadLocks(String moduleName) {
		Map<String, Integer> map = localReadLocks.get();
		if (map == null) {
			map = new HashMap<String, Integer>();
			localReadLocks.set(map);
		}
		Integer ret = map.get(moduleName);
		if (ret == null)
			return 0;
		return ret;
	}

	private void setLocalReadLocks(String moduleName, int locks) {
		Map<String, Integer> map = localReadLocks.get();
		if (map == null) {
			map = new HashMap<String, Integer>();
			localReadLocks.set(map);
		}
		map.put(moduleName, locks);
	}

	private void requestReadLock(String moduleName) throws NoSuchModuleException, TypeStorageException {
		if (!storage.checkModuleExist(moduleName))
			throw new NoSuchModuleException(moduleName);
		requestReadLockNM(moduleName);
	}
		
	private void requestReadLockNM(String moduleName) throws TypeStorageException {
		int lrl = getLocalReadLocks(moduleName);
		if (lrl == 0) {
			final ModuleState ms = getModuleState(moduleName);
			synchronized (ms) {
				long startTime = System.currentTimeMillis();
				while (ms.writerCount > 0) {
					try {
						ms.wait(10000);
					} catch (InterruptedException ignore) {}
					if (System.currentTimeMillis() - startTime > maxDeadLockWaitTime)
						throw new IllegalStateException("Looks like deadlock");
				}
				ms.readerCount++;
				//new Exception("moduleName=" + moduleName + ", readerCount=" + ms.readerCount).printStackTrace(System.out);
			}
		}
		setLocalReadLocks(moduleName, lrl + 1);
	}
	
	private void releaseReadLock(String moduleName) {
		final ModuleState ms = getModuleState(moduleName);
		int lrl = getLocalReadLocks(moduleName);
		lrl--;
		setLocalReadLocks(moduleName, lrl);
		if (lrl == 0) {
			synchronized (ms) {
				if (ms.readerCount == 0)
					throw new IllegalStateException("Can not release empty read lock");
				ms.readerCount--;
				//new Exception("moduleName=" + moduleName + ", readerCount=" + ms.readerCount).printStackTrace(System.out);
				ms.notifyAll();
			}		
		}
	}
	
	private void requestWriteLock(String moduleName) {
		final ModuleState ms = getModuleState(moduleName);
		synchronized (ms) {
			if (ms.writerCount > 0)
				throw new IllegalStateException("Concurent changes of module " + moduleName);
			ms.writerCount++;
			//new Exception("moduleName=" + moduleName + ", writerCount=" + ms.writerCount).printStackTrace(System.out);
			long startTime = System.currentTimeMillis();
			while (ms.readerCount > 0) {
				try {
					ms.wait(10000);
				} catch (InterruptedException ignore) {}
				if (System.currentTimeMillis() - startTime > maxDeadLockWaitTime) {
					ms.writerCount--;
					throw new IllegalStateException("Looks like deadlock");
				}
			}
		}
	}
	
	private void releaseWriteLock(String moduleName) {
		final ModuleState ms = getModuleState(moduleName);
		synchronized (ms) {
			if (ms.writerCount == 0)
				throw new IllegalStateException("Can not release empty write lock");
			ms.writerCount--;
			//new Exception("moduleName=" + moduleName + ", writerCount=" + ms.writerCount).printStackTrace(System.out);
			ms.notifyAll();
		}		
	}
	
	/**
	 * Retrieve a Json Schema Document for the typed object specified.  If no version numbers
	 * are indicated, the latest version is returned.  If the major version only is specified,
	 * then the latest version that is backwards compatible with the major version is returned.
	 * If exact major/minor version numbers are given, that is the exact version that is returned.
	 * @param typeDefId
	 * @return
	 * @throws NoSuchTypeException
	 * @throws NoSuchModuleException
	 * @throws TypeStorageException
	 */
	public String getJsonSchemaDocument(final TypeDefId typeDefId)
			throws NoSuchTypeException, NoSuchModuleException, TypeStorageException {
		String moduleName = typeDefId.getType().getModule();
		requestReadLock(moduleName);
		try {
			return getJsonSchemaDocumentNL(typeDefId);
		} finally {
			releaseReadLock(moduleName);
		}
	}
	
	private String getJsonSchemaDocumentNL(final TypeDefId typeDefId)
			throws NoSuchTypeException, NoSuchModuleException, TypeStorageException {
		// first make sure that the json schema document can be found
		AbsoluteTypeDefId absTypeDefId = resolveTypeDefIdNL(typeDefId);
		String typeName = absTypeDefId.getType().getName();
		// second retrieve the document if it is available
		SemanticVersion schemaDocumentVer = new SemanticVersion(absTypeDefId.getMajorVersion(), absTypeDefId.getMinorVersion());
		String moduleName = typeDefId.getType().getModule();
		String ret = storage.getTypeSchemaRecord(moduleName,typeName,schemaDocumentVer.toString());
		if (ret == null)
			throw new NoSuchTypeException("Unable to read type schema record: '"+moduleName+"."+typeName+"'");
		return ret;
	}
	
	private long findModuleVersion(ModuleDefId moduleDef) throws NoSuchModuleException, TypeStorageException {
		if (moduleDef.getVersion() == null) {
			checkModuleSupported(moduleDef.getModuleName());
			return storage.getLastReleasedModuleVersion(moduleDef.getModuleName());
		}
		long version = moduleDef.getVersion();
		if (!storage.checkModuleInfoRecordExist(moduleDef.getModuleName(), version))
			throw new NoSuchModuleException("There is no information about module " + moduleDef.getModuleName() + 
					" for version " + version);
		return version;
	}
	
	public Map<AbsoluteTypeDefId, String> getJsonSchemasForAllTypes(ModuleDefId moduleDef) 
			throws NoSuchModuleException, TypeStorageException {
		String moduleName = moduleDef.getModuleName();
		requestReadLock(moduleName);
		try {
			long moduleVersion = findModuleVersion(moduleDef);
			ModuleInfo info = storage.getModuleInfoRecord(moduleName, moduleVersion);
			Map<AbsoluteTypeDefId, String> ret = new HashMap<AbsoluteTypeDefId, String>();
			for (TypeInfo ti : info.getTypes().values()) {
				String typeVersionText = ti.getTypeVersion();
				String jsonSchema = storage.getTypeSchemaRecord(moduleName, ti.getTypeName(), typeVersionText);
				SemanticVersion typeVer = new SemanticVersion(typeVersionText);
				ret.put(new AbsoluteTypeDefId(new TypeDefName(moduleName, ti.getTypeName()), 
						typeVer.getMajor(), typeVer.getMinor()), jsonSchema);
			}
			return ret;
		} finally {
			releaseReadLock(moduleName);
		}
	}
	
	/**
	 * Given a typeDefId that may not be valid or have major/minor versions defined,
	 * attempt to lookup if a specific type definition can be resolved in the database.
	 * If a specific type definition is found, it is returned; else an exception is thrown.
	 * @param typeDefId
	 * @return
	 * @throws NoSuchTypeException
	 * @throws NoSuchModuleException
	 * @throws TypeStorageException
	 */
	public AbsoluteTypeDefId resolveTypeDefId(final TypeDefId typeDefId) 
			throws NoSuchTypeException, NoSuchModuleException, TypeStorageException {
		String moduleName = typeDefId.getType().getModule();
		requestReadLock(moduleName);
		try {
			return resolveTypeDefIdNL(typeDefId);
		} finally {
			releaseReadLock(moduleName);
		}
	}

	private AbsoluteTypeDefId resolveTypeDefIdNL(final TypeDefId typeDefId) 
			throws NoSuchTypeException, NoSuchModuleException, TypeStorageException {
		String moduleName = typeDefId.getType().getModule();
		checkModuleRegistered(moduleName);
		SemanticVersion schemaDocumentVer = findTypeVersion(typeDefId);
		if (schemaDocumentVer == null)
			throwNoSuchTypeException(typeDefId);
		String typeName = typeDefId.getType().getName();
		String ret = storage.getTypeSchemaRecord(moduleName,typeName,schemaDocumentVer.toString());
		if (ret == null)
			throw new NoSuchTypeException("Unable to read type schema record: '"+moduleName+"."+typeName+"'");
		// TODO: use this instead, but not yet supported with Mongo Storage backend
		//if(!storage.checkTypeSchemaRecordExists(moduleName, typeName, schemaDocumentVer.toString()))
		//	throw new NoSuchTypeException("Unable to read type schema record: '"+moduleName+"."+typeName+"'");
		return new AbsoluteTypeDefId(new TypeDefName(moduleName,typeName),schemaDocumentVer.getMajor(),schemaDocumentVer.getMinor());
	}
	
	/**
	 * Retrieve a Json Schema object that can be used for json validation for the most recent
	 * version of the typed object specified
	 * @param typeDefName
	 * @return
	 * @throws NoSuchTypeException
	 * @throws NoSuchModuleException
	 * @throws BadJsonSchemaDocumentException
	 * @throws TypeStorageException
	 */
	public JsonSchema getJsonSchema(TypeDefName typeDefName)
			throws NoSuchTypeException, NoSuchModuleException, BadJsonSchemaDocumentException, TypeStorageException {
		return getJsonSchema(new TypeDefId(typeDefName));
	}
	
	/**
	 * Retrieve a Json Schema objec tha can be used for json validation for the typed object specified.
	 * If no version numbers are indicated, the latest version is returned.  If the major version only
	 * is specified, then the latest version that is backwards compatible with the major version is returned.
	 * If exact major/minor version numbers are given, that is the exact version that is returned.
	 * @param typeDefId
	 * @return
	 * @throws NoSuchTypeException
	 * @throws NoSuchModuleException
	 * @throws BadJsonSchemaDocumentException
	 * @throws TypeStorageException
	 */
	public JsonSchema getJsonSchema(final TypeDefId typeDefId)
			throws NoSuchTypeException, NoSuchModuleException, BadJsonSchemaDocumentException, TypeStorageException {
		String moduleName = typeDefId.getType().getModule();
		requestReadLock(moduleName);
		try {
			String jsonSchemaDocument = getJsonSchemaDocumentNL(typeDefId);
			try {
				JsonNode schemaRootNode = mapper.readTree(jsonSchemaDocument);
				return jsonSchemaFactory.getJsonSchema(schemaRootNode);
			} catch (Exception e) {
				throw new BadJsonSchemaDocumentException("schema for typed object '"+typeDefId.getTypeString()+"'" +
						"was not a valid or readable JSON document",e);
			}
		} finally {
			releaseReadLock(moduleName);
		}
	}
	
	/**
	 * Convert a Json Schema Document into a Json Schema object that can be used for json validation.
	 * @param jsonSchemaDocument
	 * @return
	 * @throws BadJsonSchemaDocumentException
	 * @throws TypeStorageException
	 */
	protected JsonSchema jsonSchemaFromString(String jsonSchemaDocument)
			throws BadJsonSchemaDocumentException, TypeStorageException {
		try {
			JsonNode schemaRootNode = mapper.readTree(jsonSchemaDocument);
			return jsonSchemaFactory.getJsonSchema(schemaRootNode);
		} catch (Exception e) {
			throw new BadJsonSchemaDocumentException("string was not a valid or readable JSON Schema document",e);
		}
	}
	
	/**
	 * Given a moduleName and typeName, return the SPEC parsing document for the type. No version
	 * number is specified, so the latest version of document will be returned.
	 * @param moduleName
	 * @param typeName
	 * @return JSON Schema document as a String
	 * @throws NoSuchTypeException
	 */
	public KbTypedef getTypeParsingDocument(TypeDefName type) 
			throws NoSuchTypeException, NoSuchModuleException, TypeStorageException {
		return getTypeParsingDocument(new TypeDefId(type));
	}

	/**
	 * Check if module spec-file was registered at least once.
	 * @param moduleName
	 * @return true if module spec-file was registered at least once
	 * @throws TypeStorageException
	 */
	public boolean isValidModule(String moduleName) throws TypeStorageException {
		try {
			requestReadLock(moduleName);
		} catch (NoSuchModuleException ignore) {
			return false;
		}
		try {
			return isValidModuleNL(moduleName, null);
		} finally {
			releaseReadLock(moduleName);
		}
	}

	private boolean isValidModuleNL(String moduleName, Long version) throws TypeStorageException {
		if (!storage.checkModuleExist(moduleName))
			return false;
		if (version == null) {
			if (!isModuleSupported(moduleName))
				return false;
			version = storage.getLastReleasedModuleVersion(moduleName);
		}
		return storage.checkModuleInfoRecordExist(moduleName, version) && 
				storage.checkModuleSpecRecordExist(moduleName, version);
	}
	
	private void checkModule(String moduleName, Long version) throws NoSuchModuleException, TypeStorageException {
		if (!isValidModuleNL(moduleName, version))
			throw new NoSuchModuleException("Module wasn't uploaded: " + moduleName);
	}

	private void checkModuleRegistered(String moduleName) throws NoSuchModuleException, TypeStorageException {
		if ((!storage.checkModuleExist(moduleName)) || (!storage.checkModuleInfoRecordExist(moduleName,
				storage.getLastReleasedModuleVersion(moduleName))))
			throw new NoSuchModuleException("Module wasn't registered: " + moduleName);
	}
	
	/**
	 * Determine if the type is registered and valid.
	 * @param typeDefName
	 * @return true if valid, false otherwise
	 * @throws TypeStorageException
	 */
	public boolean isValidType(TypeDefName typeDefName) throws TypeStorageException {
		return isValidType(new TypeDefId(typeDefName));
	}
	
	/**
	 * Determines if the type is registered and valid.  If version numbers are set, the specific version
	 * specified must also resolve to a valid type definition. 
	 * @param typeDef
	 * @return true if valid, false otherwise
	 * @throws TypeStorageException
	 */
	public boolean isValidType(TypeDefId typeDefId) throws TypeStorageException {
		String moduleName = typeDefId.getType().getModule();
		try {
			requestReadLock(moduleName);
		} catch (NoSuchModuleException e) {
			return false;
		}
		try {
			String typeName = typeDefId.getType().getName();
			if (!storage.checkModuleExist(moduleName))
				return false;
			if (!storage.checkModuleInfoRecordExist(moduleName, 
					storage.getLastReleasedModuleVersion(moduleName)))
				return false;
			SemanticVersion ver = findTypeVersion(typeDefId);
			if (ver == null)
				return false;
			return storage.checkTypeSchemaRecordExists(moduleName, typeName, ver.toString());
		} finally {
			releaseReadLock(moduleName);
		}
	}

	private boolean isTypePresent(String moduleName, String typeName) throws TypeStorageException {
		ModuleInfo mi;
		try {
			mi = getModuleInfoNL(moduleName);
		} catch (NoSuchModuleException e) {
			return false;
		}
		return mi.getTypes().get(typeName) != null;
	}

	private SemanticVersion findTypeVersion(TypeDefId typeDef) throws TypeStorageException {
		if (typeDef.isAbsolute()) {
			if (typeDef.getMd5() != null) {
				SemanticVersion ret = null;
				for (String verText : storage.getTypeVersionsByMd5(typeDef.getType().getModule(), 
						typeDef.getType().getName(), typeDef.getMd5().getMD5())) {
					SemanticVersion version = new SemanticVersion(verText);
					if (ret == null || ret.compareTo(version) < 0)
						ret = version;
				}
				return ret;
			}
			return new SemanticVersion(typeDef.getMajorVersion(), typeDef.getMinorVersion());
		}
		if (!isModuleSupported(typeDef.getType().getModule()))
			return null;
		if (typeDef.getMajorVersion() != null) {
			Map<String, Boolean> versions = storage.getAllTypeVersions(typeDef.getType().getModule(), 
					typeDef.getType().getName());
			SemanticVersion ret = null;
			for (String verText : versions.keySet()) {
				if (!versions.get(verText))
					continue;
				SemanticVersion ver = new SemanticVersion(verText);
				if (ver.getMajor() == typeDef.getMajorVersion() && 
						(ret == null || ret.compareTo(ver) < 0))
					ret = ver;
			}
			return ret;
		}
		return findLastTypeVersion(typeDef.getType().getModule(), typeDef.getType().getName(), false);
	}

	public AbsoluteTypeDefId getTypeMd5Version(TypeDefName typeDef) 
			throws TypeStorageException, NoSuchTypeException, NoSuchModuleException {
		return getTypeMd5Version(new TypeDefId(typeDef));
	}
	
	public AbsoluteTypeDefId getTypeMd5Version(TypeDefId typeDef) 
			throws TypeStorageException, NoSuchTypeException, NoSuchModuleException {
		String moduleName = typeDef.getType().getModule();
		requestReadLock(moduleName);
		try {
			SemanticVersion version = findTypeVersion(typeDef);
			if (version == null)
				throwNoSuchTypeException(typeDef);
			return new AbsoluteTypeDefId(typeDef.getType(),
					new MD5(storage.getTypeMd5(moduleName, 
					typeDef.getType().getName(), version.toString())));
		} finally {
			releaseReadLock(moduleName);
		}
	}

	public List<AbsoluteTypeDefId> getTypeVersionsForMd5(TypeDefId typeDef) 
			throws TypeStorageException, NoSuchTypeException, NoSuchModuleException {
		if (typeDef.getMd5() == null)
			throw new NoSuchTypeException("MD5 part is not defined for type " + typeDef.getTypeString());
		String moduleName = typeDef.getType().getModule();
		requestReadLock(moduleName);
		try {
			List<String> versions = storage.getTypeVersionsByMd5(moduleName, typeDef.getType().getName(), 
					typeDef.getMd5().getMD5());
			List<AbsoluteTypeDefId> ret = new ArrayList<AbsoluteTypeDefId>();
			for (String ver : versions) {
				SemanticVersion sver = new SemanticVersion(ver);
				ret.add(new AbsoluteTypeDefId(typeDef.getType(), sver.getMajor(), sver.getMinor()));
			}
			return ret;
		} finally {
			releaseReadLock(moduleName);
		}
	}

	private SemanticVersion findLastTypeVersion(String moduleName, String typeName, 
			boolean withNoLongerSupported) throws TypeStorageException {
		if (!isTypePresent(moduleName, typeName))
			return null;
		ModuleInfo mi;
		try {
			mi = getModuleInfoNL(moduleName);
		} catch (NoSuchModuleException e) {
			return null;
		}
		return findLastTypeVersion(mi, typeName, withNoLongerSupported);
	}
	
	private SemanticVersion findLastTypeVersion(ModuleInfo module, String typeName, 
			boolean withNoLongerSupported) {
		TypeInfo ti = module.getTypes().get(typeName);
		if (ti == null || !(ti.isSupported() || withNoLongerSupported) || ti.getTypeVersion() == null)
			return null;
		return new SemanticVersion(ti.getTypeVersion());
	}
	
	protected void throwNoSuchTypeException(String moduleName, String typeName,
			String version) throws NoSuchTypeException {
		throw new NoSuchTypeException("Unable to locate type: '"+moduleName+"."+typeName+"'" + 
				(version == null ? "" : (" for version " + version)));
	}

	protected void throwNoSuchTypeException(TypeDefId typeDef) throws NoSuchTypeException {
		throw new NoSuchTypeException("Unable to locate type: " + typeDef.getTypeString());
	}

	protected void throwNoSuchFuncException(String moduleName, String funcName,
			String version) throws NoSuchFuncException {
		throw new NoSuchFuncException("Unable to locate function: '"+moduleName+"."+funcName+"'" + 
				(version == null ? "" : (" for version " + version)));
	}

	public List<String> getAllRegisteredTypes(String moduleName) 
			throws NoSuchModuleException, TypeStorageException {
		checkModuleSupported(moduleName);
		return getAllRegisteredTypes(new ModuleDefId(moduleName));
	}
	
	public List<String> getAllRegisteredTypes(ModuleDefId moduleDef) 
			throws NoSuchModuleException, TypeStorageException {
		requestReadLock(moduleDef.getModuleName());
		try {
			List<String> ret = new ArrayList<String>();
			for (TypeInfo typeInfo : getModuleInfoNL(moduleDef.getModuleName(), 
					findModuleVersion(moduleDef)).getTypes().values())
				if (typeInfo.isSupported())
					ret.add(typeInfo.getTypeName());
			return ret;
		} finally {
			releaseReadLock(moduleDef.getModuleName());
		}
	}
	
	/**
	 * Return latest version of specified type. Version has two level structure of integers 
	 * divided by dot like &lt;major&gt;.&lt;minor&gt;
	 * @param moduleName
	 * @param typeName
	 * @return latest version of specified type
	 */
	public String getLatestTypeVersion(TypeDefName type) 
			throws NoSuchTypeException, NoSuchModuleException, TypeStorageException {
		String moduleName = type.getModule();
		requestReadLock(moduleName);
		try {
			checkModule(type.getModule(), null);
			SemanticVersion ret = findLastTypeVersion(type.getModule(), type.getName(), false);
			if (ret == null)
				throwNoSuchTypeException(type.getModule(), type.getName(), null);
			return ret.toString();
		} finally {
			releaseReadLock(moduleName);
		}
	}
	
	private String saveType(ModuleInfo mi, String typeName, String jsonSchemaDocument,
			KbTypedef specParsing, boolean notBackwardCompatible, Set<RefInfo> dependencies,
			long newModuleVersion) throws NoSuchModuleException, TypeStorageException {
		TypeInfo ti = mi.getTypes().get(typeName);
		if (ti == null) {
			ti = new TypeInfo();
			ti.setTypeName(typeName);
			mi.getTypes().put(typeName, ti);
		}
		ti.setSupported(true);
		return saveType(mi, ti, jsonSchemaDocument, specParsing, notBackwardCompatible, 
				dependencies, newModuleVersion);
	}
	
	private String saveType(ModuleInfo mi, TypeInfo ti, String jsonSchemaDocument,
			KbTypedef specParsing, boolean notBackwardCompatible, Set<RefInfo> dependencies, 
			long newModuleVersion) throws NoSuchModuleException, TypeStorageException {
		SemanticVersion version = getIncrementedVersion(mi, ti.getTypeName(),
				notBackwardCompatible);
		ti.setTypeVersion(version.toString());
		return saveType(mi, ti, jsonSchemaDocument, specParsing, dependencies, newModuleVersion);
	}

	protected SemanticVersion getIncrementedVersion(ModuleInfo mi, String typeName,
			boolean notBackwardCompatible) {
		SemanticVersion version = findLastTypeVersion(mi, typeName, true);
		if (version == null) {
			version = defaultVersion;
		} else {
			int major = version.getMajor();
			int minor = version.getMinor();
			if (major > 0 && notBackwardCompatible) {
				major++;
				minor = 0;
			} else {
				minor++;
			}
			version = new SemanticVersion(major, minor);
		}
		return version;
	}
	
	private String saveType(ModuleInfo mi, TypeInfo ti, String jsonSchemaDocument,
			KbTypedef specParsing, Set<RefInfo> dependencies, long newModuleVersion) 
					throws NoSuchModuleException, TypeStorageException {
		if (dependencies != null)
			for (RefInfo ri : dependencies) {
				ri.setDepVersion(ti.getTypeVersion());
				ri.setDepModuleVersion(newModuleVersion);
				updateInternalRefVersion(ri, mi);
			}
		String md5 = DigestUtils.md5Hex(jsonSchemaDocument);
		storage.writeTypeSchemaRecord(mi.getModuleName(), ti.getTypeName(), ti.getTypeVersion(), 
				newModuleVersion, jsonSchemaDocument, md5);
		writeTypeParsingFile(mi.getModuleName(), ti.getTypeName(), ti.getTypeVersion(), 
				specParsing, newModuleVersion);
		return ti.getTypeVersion();
	}

	private void updateInternalRefVersion(RefInfo ri, ModuleInfo mi) {
		if (ri.getRefVersion() == null) {
			if (!ri.getRefModule().equals(mi.getModuleName()))
				throw new IllegalStateException("Type reference has no refVersion but reference " +
						"is not internal: " + ri);
		}
		if (ri.getRefModule().equals(mi.getModuleName())) {
			TypeInfo ti = mi.getTypes().get(ri.getRefName());
			if (ti == null)
				throw new IllegalStateException("Type reference was not found: " + ri);
			ri.setRefVersion(ti.getTypeVersion());
		}
	}
	
	private void writeTypeParsingFile(String moduleName, String typeName, String version, 
			KbTypedef document, long newModuleVersion) throws TypeStorageException {
		try {
			StringWriter sw = new StringWriter();
			mapper.writeValue(sw, document.getData());
			sw.close();
			storage.writeTypeParseRecord(moduleName, typeName, version, newModuleVersion, sw.toString());
		} catch (IOException ex) {
			throw new IllegalStateException("Unexpected internal error: " + ex.getMessage(), ex);
		}
	}
	
	private boolean checkUserIsOwnerOrAdmin(String moduleName, String userId) 
			throws NoSuchPrivilegeException, TypeStorageException {
		if (uip.isAdmin(userId))
			return true;
		Map<String, OwnerInfo> owners = storage.getOwnersForModule(moduleName);
		if (!owners.containsKey(userId))
			throw new NoSuchPrivilegeException("User " + userId + " is not in list of owners of module " + 
					moduleName);
		return owners.get(userId).isWithChangeOwnersPrivilege();
	}

	public List<String> getModuleOwners(String moduleName) throws NoSuchModuleException, TypeStorageException {
		requestReadLock(moduleName);
		try {
			checkModuleRegistered(moduleName);
			return new ArrayList<String>(storage.getOwnersForModule(moduleName).keySet());
		} finally {
			releaseReadLock(moduleName);
		}
	}

	public boolean isOwnerOfModule(String moduleName, String userId) throws NoSuchModuleException, TypeStorageException {
		requestReadLock(moduleName);
		try {
			checkModuleRegistered(moduleName);
			return storage.getOwnersForModule(moduleName).containsKey(userId);
		} finally {
			releaseReadLock(moduleName);
		}
	}

	/**
	 * Change major version of every registered type to 1.0 for types of version 0.x or set module releaseVersion to currentVersion.
	 * @param moduleName
	 * @param userId
	 * @return new versions of types
	 */
	public List<AbsoluteTypeDefId> releaseModule(String moduleName, String userId)
			throws NoSuchModuleException, TypeStorageException, NoSuchPrivilegeException {
		checkUserIsOwnerOrAdmin(moduleName, userId);
		checkModuleRegistered(moduleName);
		checkModuleSupported(moduleName);
		long version = storage.getLastModuleVersionWithUnreleased(moduleName);
		checkModule(moduleName, version);
		ModuleInfo info = storage.getModuleInfoRecord(moduleName, version);
		//List<AbsoluteTypeDefId> ret = new ArrayList<AbsoluteTypeDefId>();
		requestWriteLock(moduleName);
		try {
			List<String> typesTo10 = new ArrayList<String>();
			for (String type : info.getTypes().keySet())
				if (new SemanticVersion(info.getTypes().get(type).getTypeVersion()).getMajor() == 0)
					typesTo10.add(type);
			List<String> funcsTo10 = new ArrayList<String>();
			for (String func : info.getFuncs().keySet())
				if (new SemanticVersion(info.getFuncs().get(func).getFuncVersion()).getMajor() == 0)
					funcsTo10.add(func);
			if (typesTo10.size() > 0 || funcsTo10.size() > 0) {
				info.setUploadUserId(userId);
				info.setUploadMethod("releaseModule");
				long transactionStartTime = storage.generateNewModuleVersion(moduleName);
				try {
					Set<RefInfo> newTypeRefs = new TreeSet<RefInfo>();
					Set<RefInfo> newFuncRefs = new TreeSet<RefInfo>();
					for (String type : typesTo10) {
						String typeName = type;
						TypeInfo ti = info.getTypes().get(typeName);
						String jsonSchemaDocument = storage.getTypeSchemaRecord(moduleName, type, ti.getTypeVersion());
						Set<RefInfo> deps = storage.getTypeRefsByDep(moduleName, typeName, ti.getTypeVersion());
						try {
							KbTypedef specParsing = getTypeParsingDocumentNL(new TypeDefId(moduleName + "." + type, ti.getTypeVersion()));
							ti.setTypeVersion(releaseVersion.toString());
							saveType(info, ti, jsonSchemaDocument, specParsing, deps, transactionStartTime);
							newTypeRefs.addAll(deps);
						} catch (NoSuchTypeException ex) {
							throw new IllegalStateException(ex);  // Can not occur anyways
						}
						//ret.add(new AbsoluteTypeDefId(new TypeDefName(moduleName, type), newVersion.getMajor(), newVersion.getMinor()));
					}
					for (String funcName : funcsTo10) {
						FuncInfo fi = info.getFuncs().get(funcName);
						Set<RefInfo> deps = storage.getFuncRefsByDep(moduleName, funcName, fi.getFuncVersion());
						try {
							KbFuncdef specParsing = getFuncParsingDocumentNL(moduleName, funcName, fi.getFuncVersion());
							fi.setFuncVersion(releaseVersion.toString());
							saveFunc(info, fi, specParsing, deps, transactionStartTime);
							newFuncRefs.addAll(deps);
						} catch (NoSuchFuncException ex) {
							throw new IllegalStateException(ex);  // Can not occur anyways
						}
					}
					String specDocument = storage.getModuleSpecRecord(info.getModuleName(), version);
					writeModuleInfoSpec(info, specDocument, transactionStartTime);
					storage.addRefs(newTypeRefs, newFuncRefs);
					storage.setModuleReleaseVersion(moduleName, transactionStartTime);
					transactionStartTime = -1;
				} finally {
					if (transactionStartTime > 0)
						rollbackModuleTransaction(moduleName, transactionStartTime);
				}
			} else {
				storage.setModuleReleaseVersion(moduleName, version);
			}
		} finally {
			releaseWriteLock(moduleName);
		}
		List<AbsoluteTypeDefId> ret = new ArrayList<AbsoluteTypeDefId>();
		for (TypeInfo ti : info.getTypes().values()) {
			SemanticVersion typeVersion = new SemanticVersion(ti.getTypeVersion());
			ret.add(new AbsoluteTypeDefId(new TypeDefName(moduleName, ti.getTypeName()), 
					typeVersion.getMajor(), typeVersion.getMinor()));
		}
		return ret;
	}
	
	/**
	 * Given a moduleName, a typeName and version, return the JSON Schema document for the type. If 
	 * version parameter is null (no version number is specified) then the latest version of document 
	 * will be returned.
	 * @param moduleName
	 * @param typeName
	 * @param version
	 * @return JSON Schema document as a String
	 * @throws NoSuchTypeException
	 */
	public KbTypedef getTypeParsingDocument(TypeDefId typeDef) 
			throws NoSuchTypeException, NoSuchModuleException, TypeStorageException {
		String moduleName = typeDef.getType().getModule();
		requestReadLock(moduleName);
		try {
			return getTypeParsingDocumentNL(typeDef);
		} finally {
			releaseReadLock(moduleName);
		}
	}
		
	private KbTypedef getTypeParsingDocumentNL(TypeDefId typeDef) 
			throws NoSuchTypeException, NoSuchModuleException, TypeStorageException {
		String moduleName = typeDef.getType().getModule();
		String typeName = typeDef.getType().getName();
		checkModuleRegistered(moduleName);
		SemanticVersion documentVer = findTypeVersion(typeDef);
		if (documentVer == null)
			throwNoSuchTypeException(typeDef);
		String ret = storage.getTypeParseRecord(moduleName, typeName, documentVer.toString());
		if (ret == null)
			throw new NoSuchTypeException("Unable to read type parse record: '"+moduleName+"."+typeName+"'");
		try {
			Map<?,?> data = mapper.readValue(ret, Map.class);
			return new KbTypedef().loadFromMap(data);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
	
	private void rollbackModuleTransaction(String moduleName, long versionTime) {
		try {
			TreeSet<Long> allVers = new TreeSet<Long>(storage.getAllModuleVersions(moduleName).keySet());
			if (allVers.last() == versionTime) {
				allVers.remove(allVers.last());
			}
			storage.removeModuleVersionAndSwitchIfNotCurrent(moduleName, versionTime, allVers.last());
		} catch (Throwable ignore) {
			ignore.printStackTrace();
		}
	}

	private void writeModuleInfoSpec(ModuleInfo info, String specDocument, 
			long backupTime) throws TypeStorageException {
		storage.writeModuleRecords(info, specDocument, backupTime);
	}

	public String getModuleSpecDocument(String moduleName) 
			throws NoSuchModuleException, TypeStorageException {
		requestReadLock(moduleName);
		try {
			checkModuleSupported(moduleName);
			return storage.getModuleSpecRecord(moduleName, storage.getLastReleasedModuleVersion(moduleName));
		} finally {
			releaseReadLock(moduleName);
		}
	}

	public String getModuleSpecDocument(String moduleName, long version) 
			throws NoSuchModuleException, TypeStorageException {
		requestReadLock(moduleName);
		try {
			checkModule(moduleName, version);
			return storage.getModuleSpecRecord(moduleName, version);
		} finally {
			releaseReadLock(moduleName);
		}
	}

	public String getModuleSpecDocument(ModuleDefId moduleDef) 
			throws NoSuchModuleException, TypeStorageException {
		String moduleName = moduleDef.getModuleName();
		requestReadLock(moduleName);
		try {
			checkModuleRegistered(moduleName);
			long version = findModuleVersion(moduleDef);
			checkModule(moduleName, version);
			return storage.getModuleSpecRecord(moduleName, version);
		} finally {
			releaseReadLock(moduleName);
		}
	}

	private ModuleInfo getModuleInfoNL(String moduleName) 
			throws NoSuchModuleException, TypeStorageException {
		checkModuleSupported(moduleName);
		return getModuleInfoNL(moduleName, storage.getLastReleasedModuleVersion(moduleName));
	}
	
	public ModuleInfo getModuleInfo(String moduleName) 
			throws NoSuchModuleException, TypeStorageException {
		requestReadLock(moduleName);
		try {
			return getModuleInfoNL(moduleName);
		} finally {
			releaseReadLock(moduleName);
		}
	}

	private ModuleInfo getModuleInfoNL(String moduleName, long version) 
			throws NoSuchModuleException, TypeStorageException {
		checkModuleRegistered(moduleName);
		return storage.getModuleInfoRecord(moduleName, version);
	}
	
	public ModuleInfo getModuleInfo(String moduleName, long version) 
			throws NoSuchModuleException, TypeStorageException {
		requestReadLock(moduleName);
		try {
			return getModuleInfoNL(moduleName, version);
		} finally {
			releaseReadLock(moduleName);
		}
	}

	public ModuleInfo getModuleInfo(ModuleDefId moduleDef) 
			throws NoSuchModuleException, TypeStorageException {
		String moduleName = moduleDef.getModuleName();
		requestReadLock(moduleName);
		try {
			return getModuleInfoNL(moduleName, findModuleVersion(moduleDef));
		} finally {
			releaseReadLock(moduleName);
		}
	}

	public long getLatestModuleVersion(String moduleName) 
			throws NoSuchModuleException, TypeStorageException {
		requestReadLock(moduleName);
		try {
			checkModuleRegistered(moduleName);
			checkModuleSupported(moduleName);
			return storage.getLastReleasedModuleVersion(moduleName);
		} finally {
			releaseReadLock(moduleName);
		}
	}
	
	public long getLatestModuleVersionWithUnreleased(String moduleName, String userId) 
			throws NoSuchModuleException, TypeStorageException, NoSuchPrivilegeException {
		checkUserIsOwnerOrAdmin(moduleName, userId);
		requestReadLock(moduleName);
		try {
			checkModuleRegistered(moduleName);
			return storage.getLastModuleVersionWithUnreleased(moduleName);
		} finally {
			releaseReadLock(moduleName);
		}
	}
	
	public List<Long> getAllModuleVersions(String moduleName) 
			throws NoSuchModuleException, TypeStorageException {
		requestReadLock(moduleName);
		try {
			checkModuleRegistered(moduleName);
			checkModuleSupported(moduleName);
			return getAllModuleVersionsNL(moduleName, false);
		} finally {
			releaseReadLock(moduleName);
		}
	}

	public List<Long> getAllModuleVersionsWithUnreleased(String moduleName, String ownerUserId) 
			throws NoSuchModuleException, TypeStorageException, NoSuchPrivilegeException {
		requestReadLock(moduleName);
		try {
			checkModuleRegistered(moduleName);
			checkModuleSupported(moduleName);
			checkUserIsOwnerOrAdmin(moduleName, ownerUserId);
			return getAllModuleVersionsNL(moduleName, true);
		} finally {
			releaseReadLock(moduleName);
		}
	}

	private List<Long> getAllModuleVersionsNL(String moduleName, boolean withUnreleased) throws TypeStorageException {
		TreeMap<Long, Boolean> map = storage.getAllModuleVersions(moduleName);
		List<Long> ret = new ArrayList<Long>();
		for (Map.Entry<Long, Boolean> enrty : map.entrySet())
			if ((withUnreleased || enrty.getValue()) && enrty.getKey() != map.firstKey())
				ret.add(enrty.getKey());
		return ret;
	}
	
	public List<String> getAllRegisteredFuncs(String moduleName) 
			throws NoSuchModuleException, TypeStorageException {
		requestReadLock(moduleName);
		try {
			List<String> ret = new ArrayList<String>();
			for (FuncInfo info : getModuleInfoNL(moduleName).getFuncs().values()) 
				if (info.isSupported())
					ret.add(info.getFuncName());
			return ret;
		} finally {
			releaseReadLock(moduleName);
		}
	}

	private SemanticVersion findLastFuncVersion(String moduleName, String funcName) throws TypeStorageException {
		try {
			return findLastFuncVersion(getModuleInfoNL(moduleName), funcName, false);
		} catch (NoSuchModuleException e) {
			return null;
		}
	}
		
	private SemanticVersion findLastFuncVersion(ModuleInfo mi, String funcName, 
			boolean withNotSupported) {
		FuncInfo fi = mi.getFuncs().get(funcName);
		if (fi == null || !(fi.isSupported() || withNotSupported) || fi.getFuncVersion() == null)
			return null;
		return new SemanticVersion(fi.getFuncVersion());
	}

	/**
	 * Return latest version of specified type. Version has two level structure of integers 
	 * divided by dot like &lt;major&gt;.&lt;minor&gt;
	 * @param moduleName
	 * @param funcName
	 * @return latest version of specified type
	 */
	public String getLatestFuncVersion(String moduleName, String funcName)
			throws NoSuchFuncException, NoSuchModuleException, TypeStorageException {
		requestReadLock(moduleName);
		try {
			checkModule(moduleName, null);
			SemanticVersion ret = findLastFuncVersion(moduleName, funcName);
			if (ret == null)
				throwNoSuchFuncException(moduleName, funcName, null);
			return ret.toString();
		} finally {
			releaseReadLock(moduleName);
		}
	}
	
	private String saveFunc(ModuleInfo mi, String funcName, KbFuncdef specParsingDocument, 
			boolean notBackwardCompatible, Set<RefInfo> dependencies, long newModuleVersion) 
					throws NoSuchModuleException, TypeStorageException {
		FuncInfo fi = mi.getFuncs().get(funcName);
		if (fi == null) {
			fi = new FuncInfo();
			fi.setFuncName(funcName);
			mi.getFuncs().put(funcName, fi);
		}
		fi.setSupported(true);
		return saveFunc(mi, fi, specParsingDocument, notBackwardCompatible, dependencies, newModuleVersion);
	}
	
	private String saveFunc(ModuleInfo mi, FuncInfo fi, KbFuncdef specParsingDocument, 
			boolean notBackwardCompatible, Set<RefInfo> dependencies, long newModuleVersion) 
					throws NoSuchModuleException, TypeStorageException {
		SemanticVersion version = findLastFuncVersion(mi, fi.getFuncName(), true);
		if (version == null) {
			version = defaultVersion;
		} else {
			int major = version.getMajor();
			int minor = version.getMinor();
			if (major > 0 && notBackwardCompatible) {
				major++;
				minor = 0;
			} else {
				minor++;
			}
			version = new SemanticVersion(major, minor);
		}
		fi.setFuncVersion(version.toString());
		return saveFunc(mi, fi, specParsingDocument, dependencies, newModuleVersion);
	}
		
	private String saveFunc(ModuleInfo mi, FuncInfo fi, KbFuncdef specParsingDocument, 
			Set<RefInfo> dependencies, long newModuleVersion) 
					throws NoSuchModuleException, TypeStorageException {
		if (dependencies != null)
			for (RefInfo dep : dependencies) {
				dep.setDepVersion(fi.getFuncVersion());
				dep.setDepModuleVersion(newModuleVersion);
				updateInternalRefVersion(dep, mi);
			}
		writeFuncParsingFile(mi.getModuleName(), fi.getFuncName(), fi.getFuncVersion(), 
				specParsingDocument, newModuleVersion);
		return fi.getFuncVersion();
	}
	
	private void writeFuncParsingFile(String moduleName, String funcName, String version, 
			KbFuncdef document, long newModuleVersion) 
			throws TypeStorageException {
		try {
			StringWriter sw = new StringWriter();
			mapper.writeValue(sw, document.getData());
			sw.close();
			storage.writeFuncParseRecord(moduleName, funcName, version.toString(), 
					newModuleVersion, sw.toString());
		} catch (TypeStorageException ex) {
			throw ex;
		} catch (IOException ex) {
			throw new IllegalStateException("Unexpected internal error: " + ex.getMessage(), ex);
		}
	}

	public KbFuncdef getFuncParsingDocument(String moduleName, String funcName) 
			throws NoSuchFuncException, NoSuchModuleException, TypeStorageException {
		return getFuncParsingDocument(moduleName, funcName, null);
	}

	public KbFuncdef getFuncParsingDocument(String moduleName, String funcName,
			String version) throws NoSuchFuncException, NoSuchModuleException, TypeStorageException {
		requestReadLock(moduleName);
		try {
			return getFuncParsingDocumentNL(moduleName, funcName, version);
		} finally {
			releaseReadLock(moduleName);
		}
	}
	
	private KbFuncdef getFuncParsingDocumentNL(String moduleName, String funcName,
			String version) throws NoSuchFuncException, NoSuchModuleException, TypeStorageException {
		checkModuleRegistered(moduleName);
		SemanticVersion curVersion = version == null ? findLastFuncVersion(moduleName, funcName) : 
			new SemanticVersion(version);
		if (curVersion == null)
			throwNoSuchFuncException(moduleName, funcName, null);
		String ret = storage.getFuncParseRecord(moduleName, funcName, curVersion.toString());
		if (ret == null)
			throwNoSuchFuncException(moduleName, funcName, version);
		try {
			Map<?,?> data = mapper.readValue(ret, Map.class);
			return new KbFuncdef().loadFromMap(data, null);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
	
	private void stopTypeSupport(ModuleInfo mi, String typeName, long newModuleVersion) 
			throws NoSuchTypeException, NoSuchModuleException, TypeStorageException {
		TypeInfo ti = mi.getTypes().get(typeName);
		if (ti == null)
			throwNoSuchTypeException(mi.getModuleName(), typeName, null);
		ti.setSupported(false);
	}
	
	public void stopTypeSupport(TypeDefName type, String userId, String uploadComment)
			throws NoSuchTypeException, NoSuchModuleException, TypeStorageException, 
			NoSuchPrivilegeException, SpecParseException {
		String moduleName = type.getModule();
		String typeName = type.getName();
		saveModule(getModuleSpecDocument(moduleName), Collections.<String>emptySet(), 
				new HashSet<String>(Arrays.asList(typeName)), userId, false, 
				Collections.<String,Long>emptyMap(), null, "stopTypeSupport", uploadComment);
	}
	
	private void stopFuncSupport(ModuleInfo info, String funcName, long newModuleVersion) 
			throws NoSuchFuncException, NoSuchModuleException, TypeStorageException {
		FuncInfo fi = info.getFuncs().get(funcName);
		if (fi == null)
			throwNoSuchFuncException(info.getModuleName(), funcName, null);
		fi.setSupported(false);
	}
	
	public void removeModule(String moduleName, String userId) 
			throws NoSuchModuleException, TypeStorageException, NoSuchPrivilegeException {
		requestWriteLock(moduleName);
		try {
			checkAdmin(userId);
			checkModuleRegistered(moduleName);
			storage.removeModule(moduleName);
		} finally {
			releaseWriteLock(moduleName);
		}
	}
	
	/**
	 * @return all names of registered modules
	 */
	public List<String> getAllRegisteredModules() throws TypeStorageException {
		return new ArrayList<String>(storage.getAllRegisteredModules(false));
	}
	
	private String getTypeVersion(TypeDefId typeDef) 
			throws NoSuchTypeException, NoSuchModuleException, TypeStorageException {
		checkModuleRegistered(typeDef.getType().getModule());
		SemanticVersion ret = findTypeVersion(typeDef);
		if (ret == null)
			throwNoSuchTypeException(typeDef);
		return ret.toString();
	}

	public Set<RefInfo> getTypeRefsByDep(TypeDefId depTypeDef) 
			throws TypeStorageException, NoSuchTypeException, NoSuchModuleException {
		String depModule = depTypeDef.getType().getModule();
		requestReadLock(depModule);
		try {
			String depType = depTypeDef.getType().getName();
			String version = getTypeVersion(depTypeDef);
			return storage.getTypeRefsByDep(depModule, depType, version);
		} finally {
			releaseReadLock(depModule);
		}
	}
	
	public Set<RefInfo> getTypeRefsByRef(TypeDefId refTypeDef) 
			throws TypeStorageException, NoSuchTypeException, NoSuchModuleException {
		String refModule = refTypeDef.getType().getModule();
		requestReadLock(refModule);
		try {
			String refType = refTypeDef.getType().getName();
			String version = getTypeVersion(refTypeDef);
			return storage.getTypeRefsByRef(refModule, refType, version);
		} finally {
			releaseReadLock(refModule);
		}
	}

	public Set<RefInfo> getFuncRefsByDep(String depModule, String depFunc) 
			throws TypeStorageException, NoSuchModuleException, NoSuchFuncException {
		requestReadLock(depModule);
		try {
			checkModuleRegistered(depModule);
			checkModuleSupported(depModule);
			return storage.getFuncRefsByDep(depModule, depFunc, null);
		} finally {
			releaseReadLock(depModule);
		}
	}

	public Set<RefInfo> getFuncRefsByDep(String depModule, String depFunc, 
			String version) throws TypeStorageException, NoSuchModuleException, NoSuchFuncException {
		requestReadLock(depModule);
		try {
			checkModuleRegistered(depModule);
			if (version == null) {
				SemanticVersion sVer = findLastFuncVersion(depModule, depFunc);
				if (sVer == null)
					throwNoSuchFuncException(depModule, depFunc, version);
				version = sVer.toString();
			}
			return storage.getFuncRefsByDep(depModule, depFunc, version);
		} finally {
			releaseReadLock(depModule);
		}
	}
	
	public Set<RefInfo> getFuncRefsByRef(TypeDefId refTypeDef) 
			throws TypeStorageException, NoSuchTypeException, NoSuchModuleException {
		String refModule = refTypeDef.getType().getModule();
		requestReadLock(refModule);
		try {
			String refType = refTypeDef.getType().getName();
			String version = getTypeVersion(refTypeDef);
			return storage.getFuncRefsByRef(refModule, refType, version);
		} finally {
			releaseReadLock(refModule);
		}
	}
	
	private File createTempDir() {
		synchronized (tempDirLock) {
			long suffix = System.currentTimeMillis();
			File ret;
			while (true) {
				ret = new File(parentTempDir, "temp_" + suffix);
				if (!ret.exists())
					break;
				suffix++;
			}
			ret.mkdirs();
			return ret;
		}
	}
	
	public void requestModuleRegistration(String moduleName, String ownerUserId)
			throws TypeStorageException {
		requestReadLockNM(moduleName);
		try {
			storage.addNewModuleRegistrationRequest(moduleName, ownerUserId);
		} finally {
			releaseReadLock(moduleName);
		}
	}
	
	public List<OwnerInfo> getNewModuleRegistrationRequests(String adminUserId) 
			throws NoSuchPrivilegeException, TypeStorageException {
		checkAdmin(adminUserId);
		return storage.getNewModuleRegistrationRequests();
	}

	private void checkAdmin(String adminUserId)
			throws NoSuchPrivilegeException {
		if (!uip.isAdmin(adminUserId))
			throw new NoSuchPrivilegeException("User " + adminUserId + " should be administrator");
	}
	
	public void approveModuleRegistrationRequest(String adminUserId, String newModuleName) 
			throws TypeStorageException, NoSuchPrivilegeException {
		checkAdmin(adminUserId);
		requestWriteLock(newModuleName);
		try {
			String newOwnerUserId = storage.getOwnerForNewModuleRegistrationRequest(newModuleName);
			autoGenerateModuleInfo(newModuleName, newOwnerUserId);
			storage.removeNewModuleRegistrationRequest(newModuleName, newOwnerUserId);
			// TODO: send notification to e-mail of requesting user
		} finally {
			releaseWriteLock(newModuleName);
		}
	}

	public void refuseModuleRegistrationRequest(String adminUserId, String newModuleName) 
			throws TypeStorageException, NoSuchPrivilegeException {
		checkAdmin(adminUserId);
		requestWriteLock(newModuleName);
		try {
			String newOwnerUserId = storage.getOwnerForNewModuleRegistrationRequest(newModuleName);
			storage.removeNewModuleRegistrationRequest(newModuleName, newOwnerUserId);
			// TODO: send notification to e-mail of requesting user
		} finally {
			releaseWriteLock(newModuleName);
		}
	}

	private void autoGenerateModuleInfo(String moduleName, String ownerUserId) throws TypeStorageException {
		if (storage.checkModuleExist(moduleName))
			throw new IllegalStateException("Module " + moduleName + " was already registered");
		ModuleInfo info = new ModuleInfo();
		info.setModuleName(moduleName);
		info.setReleased(true);
		storage.initModuleInfoRecord(info);
		storage.addOwnerToModule(moduleName, ownerUserId, true);
		storage.setModuleReleaseVersion(moduleName, info.getVersionTime());
	}

	public Map<TypeDefName, TypeChange> registerModule(String specDocument, 
			String userId) throws SpecParseException, 
			TypeStorageException, NoSuchPrivilegeException, NoSuchModuleException {
		return registerModule(specDocument, Collections.<String>emptyList(), userId);
	}

	public Map<TypeDefName, TypeChange> registerModule(String specDocument, 
			List<String> typesToSave, String userId) throws SpecParseException, 
			TypeStorageException, NoSuchPrivilegeException, NoSuchModuleException {
		return registerModule(specDocument, typesToSave, Collections.<String>emptyList(), userId);
	}
	
	public Map<TypeDefName, TypeChange> registerModule(String specDocument, 
			List<String> typesToSave, List<String> typesToUnregister, String userId) 
					throws SpecParseException, TypeStorageException, NoSuchPrivilegeException, 
					NoSuchModuleException {
		return registerModule(specDocument, typesToSave, typesToUnregister, userId, false);
	}

	public Map<TypeDefName, TypeChange> registerModule(String specDocument, 
			List<String> typesToSave, List<String> typesToUnregister, String userId, 
			boolean dryMode) throws SpecParseException, TypeStorageException, NoSuchPrivilegeException, 
			NoSuchModuleException {
		return registerModule(specDocument, typesToSave, typesToUnregister, userId, dryMode, 
				Collections.<String, Long>emptyMap());
	}

	public Map<TypeDefName, TypeChange> registerModule(String specDocument, 
			List<String> typesToSave, List<String> typesToUnregister, String userId, 
			boolean dryMode, Map<String, Long> moduleVersionRestrictions) 
					throws SpecParseException, TypeStorageException, NoSuchPrivilegeException, 
					NoSuchModuleException {
		return registerModule(specDocument, typesToSave, typesToUnregister, userId, dryMode, 
				moduleVersionRestrictions, null);
	}

	public Map<TypeDefName, TypeChange> registerModule(String specDocument, 
			List<String> typesToSave, List<String> typesToUnregister, String userId, 
			boolean dryMode, Map<String, Long> moduleVersionRestrictions, Long prevModuleVersion) 
					throws SpecParseException, TypeStorageException, NoSuchPrivilegeException, 
					NoSuchModuleException {
		return registerModule(specDocument, typesToSave, typesToUnregister, userId, dryMode, 
				moduleVersionRestrictions, prevModuleVersion, "");
	}
	
	public Map<TypeDefName, TypeChange> registerModule(String specDocument, 
			List<String> typesToSave, List<String> typesToUnregister, String userId, 
			boolean dryMode, Map<String, Long> moduleVersionRestrictions, Long prevModuleVersion,
			String uploadComment) throws SpecParseException, TypeStorageException, 
			NoSuchPrivilegeException, NoSuchModuleException {
		final Set<String> unreg;
		if (typesToUnregister == null) {
			unreg = new HashSet<String>();
		} else {
			unreg = new HashSet<String>(typesToUnregister);
		}
		return saveModule(specDocument, new HashSet<String>(typesToSave), 
				unreg, userId, dryMode, moduleVersionRestrictions, 
				prevModuleVersion, "registerModule", uploadComment);
	}

	public Map<TypeDefName, TypeChange> refreshModule(String moduleName, 
			String userId) throws SpecParseException, 
			TypeStorageException, NoSuchModuleException, NoSuchPrivilegeException {
		return refreshModule(moduleName, Collections.<String>emptyList(), userId);
	}

	public Map<TypeDefName, TypeChange> refreshModule(String moduleName, 
			List<String> typesToSave, String userId) throws SpecParseException, 
			TypeStorageException, NoSuchModuleException, NoSuchPrivilegeException {
		return refreshModule(moduleName, typesToSave, Collections.<String>emptyList(), userId);
	}
	
	public Map<TypeDefName, TypeChange> refreshModule(String moduleName, 
			List<String> typesToSave, List<String> typesToUnregister, String userId) 
					throws SpecParseException, TypeStorageException, NoSuchModuleException, 
					NoSuchPrivilegeException {
		return refreshModule(moduleName, typesToSave, typesToUnregister, userId, false);
	}

	public Map<TypeDefName, TypeChange> refreshModule(String moduleName, 
			List<String> typesToSave, List<String> typesToUnregister, String userId, 
			boolean dryMode) throws SpecParseException, TypeStorageException, NoSuchModuleException, 
			NoSuchPrivilegeException {
		return refreshModule(moduleName, typesToSave, typesToUnregister, userId, dryMode, 
				Collections.<String, Long>emptyMap());
	}

	public Map<TypeDefName, TypeChange> refreshModule(String moduleName, 
			List<String> typesToSave, List<String> typesToUnregister, String userId, 
			boolean dryMode, Map<String, Long> moduleVersionRestrictions) 
					throws SpecParseException, TypeStorageException, NoSuchModuleException, 
					NoSuchPrivilegeException {
		return refreshModule(moduleName, typesToSave, typesToUnregister, userId, dryMode, moduleVersionRestrictions, "");
	}
	
	public Map<TypeDefName, TypeChange> refreshModule(String moduleName, 
			List<String> typesToSave, List<String> typesToUnregister, String userId, 
			boolean dryMode, Map<String, Long> moduleVersionRestrictions, String uploadComment) 
					throws SpecParseException, TypeStorageException, NoSuchModuleException, 
					NoSuchPrivilegeException {
		String specDocument = getModuleSpecDocument(moduleName, storage.getLastModuleVersionWithUnreleased(moduleName));
		return saveModule(specDocument, new HashSet<String>(typesToSave), 
				new HashSet<String>(typesToUnregister), userId, dryMode, moduleVersionRestrictions, 
				null, "refreshModule", uploadComment);
	}

	private String correctSpecIncludes(String specDocument, List<String> includedModules) 
			throws SpecParseException {
		try {
			StringWriter withGoodImports = new StringWriter();
			PrintWriter pw = null; // new PrintWriter(withGoodImports);
			BufferedReader br = new BufferedReader(new StringReader(specDocument));
			List<String> headerLines = new ArrayList<String>();
			while (true) {
				String l = br.readLine();
				if (l == null)
					break;
				if (pw == null) {
					if (l.trim().isEmpty()) {
						headerLines.add("");
						continue;
					}
					if (l.startsWith("#include")) {
						l = l.substring(8).trim();
						if (!(l.startsWith("<") && l.endsWith(">")))
							throw new IllegalStateException("Wrong include structure (it should be ...<file_path>): " + l);
						l = l.substring(1, l.length() - 1).trim();
						if (l.indexOf('/') >= 0)
							l = l.substring(l.lastIndexOf('/') + 1);
						if (l.indexOf('.') >= 0)
							l = l.substring(0, l.indexOf('.')).trim();
						includedModules.add(l);
						headerLines.add("#include <" + l + ".types>");
					} else {
						pw = new PrintWriter(withGoodImports);
						for (String hl : headerLines)
							pw.println(hl);
						pw.println(l);
					}
				} else {
					pw.println(l);
				}
			}
			br.close();
			pw.close();
			return withGoodImports.toString();
		} catch (Exception ex) {
			throw new SpecParseException("Unexpected error during parsing of spec-file include declarations: " + ex.getMessage(), ex);
		}
	}
	
	private KbModule compileSpecFile(String specDocument, List<String> includedModules,
			Map<String, Map<String, String>> moduleToTypeToSchema,
			Map<String, ModuleInfo> moduleToInfo, Map<String, Long> moduleVersionRestrictions) 
					throws SpecParseException, NoSuchModuleException {
		File tempDir = kidlSource == KidlSource.internal ? null : createTempDir();
		try {
			Map<String, IncludeDependentPath> moduleToPath = new HashMap<String, IncludeDependentPath>();
			StaticIncludeProvider sip = kidlSource == KidlSource.internal ? new StaticIncludeProvider() : null;
			for (String iModule : includedModules) {
				Long iVersion = moduleVersionRestrictions.get(iModule);
				if (iVersion == null)
					iVersion = getLatestModuleVersion(iModule);
				saveIncludedModuleRecusive(tempDir, new IncludeDependentPath(), iModule, iVersion, 
						moduleToPath, moduleVersionRestrictions, sip);
			}
			for (IncludeDependentPath path : moduleToPath.values())
				moduleToInfo.put(path.info.getModuleName(), path.info);
			List<KbService> services;
			if (kidlSource == KidlSource.external) {
				File specFile = new File(tempDir, "currentlyCompiled.spec");
				writeFile(specDocument, specFile);
				services = KidlParser.parseSpec(specFile, tempDir, moduleToTypeToSchema, kbTopPath, false);
			} else if (kidlSource == KidlSource.both) {
				File specFile = new File(tempDir, "currentlyCompiled.spec");
				writeFile(specDocument, specFile);
				Map<String, Map<String, String>> jsonSchemasExt = new TreeMap<String, Map<String, String>>();
				Map<?,?> parseMapExt = null;
				Exception extErr = null;
				try {
					parseMapExt = KidlParser.parseSpecExt(specFile, tempDir, jsonSchemasExt, kbTopPath);
				} catch (Exception ex) {
					extErr = ex;
				}
				Map<String, Map<String, String>> jsonSchemasInt = new TreeMap<String, Map<String, String>>();
				Map<?,?> parseMapInt = null;
				try {
					parseMapInt = KidlParser.parseSpecInt(specFile, jsonSchemasInt);
				} catch (Exception intErr) {
					if (extErr == null)
						System.out.println("Warning: external parser didn't throw an exception");
					throw intErr;
				}
				if (extErr != null) {
					System.out.println("Warning: internal parser didn't throw an exception");
					throw extErr;
				}
				boolean ok = KidlTest.compareJson(parseMapExt, parseMapInt, "Parsing schema");
				ok = ok & KidlTest.compareJsonSchemas(jsonSchemasExt, jsonSchemasInt, "Json schemas");
				if (!ok)
					throw new SpecParseException("Output of KIDL parsers is different");
				services = KidlParser.parseSpec(parseMapExt);
				moduleToTypeToSchema.putAll(jsonSchemasExt);
			} else {
				StringReader r = new StringReader(specDocument);
				Map<?,?> parseMap = KidlParser.parseSpecInt(r, moduleToTypeToSchema, sip);
				services = KidlParser.parseSpec(parseMap);
			}
			if (services.size() != 1)
				throw new SpecParseException("Spec-file should consist of only one service");
			if (services.get(0).getModules().size() != 1)
				throw new SpecParseException("Spec-file should consist of only one module");
			return services.get(0).getModules().get(0);
		} catch (NoSuchModuleException ex) {
			throw ex;
		} catch (SpecParseException ex) {
			throw ex;
		} catch (Exception ex) {
			throw new SpecParseException("Unexpected error during spec-file parsing: " + ex.getMessage(), ex);			
		} finally {
			if (tempDir != null)
				deleteTempDir(tempDir);
		}
	}
	
	private Map<TypeDefName, TypeChange> saveModule(String specDocument, 
			Set<String> addedTypes, Set<String> unregisteredTypes, String userId, 
			boolean dryMode, Map<String, Long> moduleVersionRestrictions, Long prevModuleVersion,
			String uploadMethod, String uploadComment) throws SpecParseException, TypeStorageException, 
			NoSuchPrivilegeException, NoSuchModuleException {
		List<String> includedModules = new ArrayList<String>();
		specDocument = correctSpecIncludes(specDocument, includedModules);
		String moduleName = null;
		long transactionStartTime = -1;
		Map<String, Map<String, String>> moduleToTypeToSchema = new HashMap<String, Map<String, String>>();
		Map<String, ModuleInfo> moduleToInfo = new HashMap<String, ModuleInfo>();
		KbModule module = compileSpecFile(specDocument, includedModules, moduleToTypeToSchema, moduleToInfo, 
				moduleVersionRestrictions);
		moduleName = module.getModuleName();
		checkModuleRegistered(moduleName);
		checkModuleSupported(moduleName);
		checkUserIsOwnerOrAdmin(moduleName, userId);
		long realPrevVersion = storage.getLastModuleVersionWithUnreleased(moduleName);
		if (prevModuleVersion != null) {
			if (realPrevVersion != prevModuleVersion)
				throw new SpecParseException("Concurrent modification: previous module version is " + 
						realPrevVersion + " (but should be " + prevModuleVersion + ")");
		}
		requestWriteLock(moduleName);
		try {
			try {
				ModuleInfo info = getModuleInfoNL(moduleName, realPrevVersion);
				boolean isNew = !storage.checkModuleSpecRecordExist(moduleName, info.getVersionTime());
				String prevMd5 = info.getMd5hash();
				info.setMd5hash(DigestUtils.md5Hex(mapper.writeValueAsString(module.getData())));
				info.setDescription(module.getComment());
				Map<String, Long> includedModuleNameToVersion = new LinkedHashMap<String, Long>();
				for (String iModule : includedModules)
					includedModuleNameToVersion.put(iModule, moduleToInfo.get(iModule).getVersionTime());
				Map<String, Long> prevIncludes = info.getIncludedModuleNameToVersion();
				info.setIncludedModuleNameToVersion(includedModuleNameToVersion);
				info.setUploadUserId(userId);
				info.setUploadMethod(uploadMethod);
				info.setUploadComment(uploadComment == null ? "" : uploadComment);
				info.setReleased(false);
				Map<String, String> typeToSchema = moduleToTypeToSchema.get(moduleName);
				if (typeToSchema == null)
					throw new SpecParseException("Json schema generation was missed for module: " + moduleName);
				Set<String> oldRegisteredTypes = new HashSet<String>();
				Set<String> oldRegisteredFuncs = new HashSet<String>();
				if (!isNew) {
					for (TypeInfo typeInfo : info.getTypes().values())
						if (typeInfo.isSupported())
							oldRegisteredTypes.add(typeInfo.getTypeName());
					for (FuncInfo funcInfo : info.getFuncs().values()) 
						if (funcInfo.isSupported())
							oldRegisteredFuncs.add(funcInfo.getFuncName());
				}
				for (String type : unregisteredTypes) {
					if (!oldRegisteredTypes.contains(type))
						throw new SpecParseException("Type is in unregistering type list but was not already " +
								"registered: " + type);
				}
				for (String type : addedTypes) {
					if (oldRegisteredTypes.contains(type))
						throw new SpecParseException("Type was already registered before: " + type);
					if (unregisteredTypes.contains(type))
						throw new SpecParseException("Type couldn't be in both adding and unregistering lists: " + type);
				}
				Set<String> newRegisteredTypes = new HashSet<String>();
				newRegisteredTypes.addAll(oldRegisteredTypes);
				newRegisteredTypes.removeAll(unregisteredTypes);
				newRegisteredTypes.addAll(addedTypes);
				Set<String> allNewTypes = new HashSet<String>();
				Set<String> allNewFuncs = new HashSet<String>();
				List<ComponentChange> comps = new ArrayList<ComponentChange>();
				Map<TypeDefName, TypeChange> ret = new LinkedHashMap<TypeDefName, TypeChange>();
				for (KbModuleComp comp : module.getModuleComponents()) {
					if (comp instanceof KbTypedef) {
						KbTypedef type = (KbTypedef)comp;
						allNewTypes.add(type.getName());
						if (newRegisteredTypes.contains(type.getName())) {
							String jsonSchemaDocument = typeToSchema.get(type.getName());
							if (jsonSchemaDocument == null)
								throw new SpecParseException("Json schema wasn't generated for type: " + type.getName());
							Change change = findTypeChange(info, type);
							if (change == Change.noChange) {
								String prevJsonSchema = storage.getTypeSchemaRecord(moduleName, type.getName(), 
										info.getTypes().get(type.getName()).getTypeVersion());
								if (jsonSchemaDocument.equals(prevJsonSchema)) {
									continue;
								}
								change = Change.backwardCompatible;
							}
							Set<RefInfo> dependencies = extractTypeRefs(type, moduleToInfo, newRegisteredTypes);
							jsonSchemaFromString(jsonSchemaDocument);
							boolean notBackwardCompatible = (change == Change.notCompatible);
							comps.add(new ComponentChange(true, false, type.getName(), jsonSchemaDocument, type, null, 
									notBackwardCompatible, dependencies));
							TypeDefName typeDefName = new TypeDefName(info.getModuleName(), type.getName());
							SemanticVersion newVer = getIncrementedVersion(info, type.getName(), notBackwardCompatible);
							ret.put(typeDefName, new TypeChange(false, new AbsoluteTypeDefId(typeDefName, newVer.getMajor(), 
									newVer.getMinor()), jsonSchemaDocument));
						}
					} else if (comp instanceof KbFuncdef) {
						KbFuncdef func = (KbFuncdef)comp;
						allNewFuncs.add(func.getName());
						Change change = findFuncChange(info, func);
						if (change == Change.noChange)
							continue;
						Set<RefInfo> dependencies = new TreeSet<RefInfo>();
						for (KbParameter param : func.getParameters())
							dependencies.addAll(extractTypeRefs(moduleName, func.getName(), param, moduleToInfo, newRegisteredTypes));
						for (KbParameter param : func.getReturnType())
							dependencies.addAll(extractTypeRefs(moduleName, func.getName(), param, moduleToInfo, newRegisteredTypes));					
						boolean notBackwardCompatible = (change == Change.notCompatible);
						comps.add(new ComponentChange(false, false, func.getName(), null, null, func, notBackwardCompatible, 
								dependencies));
					}
				}
				for (String type : addedTypes) {
					if (!allNewTypes.contains(type))
						throw new SpecParseException("Type is in adding type list but is not defined in spec-file: " + type);
				}
				for (String type : newRegisteredTypes) {
					if (!allNewTypes.contains(type))
						unregisteredTypes.add(type);
				}
				for (String typeName : unregisteredTypes) {
					comps.add(new ComponentChange(true, true, typeName, null, null, null, false, null));
					TypeDefName typeDefName = new TypeDefName(info.getModuleName(), typeName);
					ret.put(typeDefName, new TypeChange(true, null, null));
				}
				for (String funcName : oldRegisteredFuncs) {
					if (!allNewFuncs.contains(funcName)) {
						comps.add(new ComponentChange(false, true, funcName, null, null, null, false, null));
					}
				}
				if (prevMd5 != null && prevMd5.equals(info.getMd5hash()) && prevIncludes.isEmpty() && 
						info.getIncludedModuleNameToVersion().isEmpty() && comps.isEmpty()) {
					String prevSpec = storage.getModuleSpecRecord(moduleName, info.getVersionTime());
					if (prevSpec.equals(specDocument))
						throw new SpecParseException("There is no difference between previous and current versions of " +
								"module " + moduleName);
				}
				if (!dryMode) {
					Set<RefInfo> createdTypeRefs = new TreeSet<RefInfo>();
					Set<RefInfo> createdFuncRefs = new TreeSet<RefInfo>();
					transactionStartTime = storage.generateNewModuleVersion(moduleName);
					for (ComponentChange comp : comps) {
						if (comp.isType) {
							if (comp.isDeletion) {
								stopTypeSupport(info, comp.name, transactionStartTime);						
							} else {
								saveType(info, comp.name, comp.jsonSchemaDocument, comp.typeParsing, comp.notBackwardCompatible, 
										comp.dependencies, transactionStartTime);					
								createdTypeRefs.addAll(comp.dependencies);
							}
						} else {
							if (comp.isDeletion) {
								stopFuncSupport(info, comp.name, transactionStartTime);
							} else {
								saveFunc(info, comp.name, comp.funcParsing, comp.notBackwardCompatible, comp.dependencies,
										transactionStartTime);
								createdFuncRefs.addAll(comp.dependencies);
							}
						}
					}
					writeModuleInfoSpec(info, specDocument, transactionStartTime);
					storage.addRefs(createdTypeRefs, createdFuncRefs);
					transactionStartTime = -1;
				}
				return ret;
			} catch (NoSuchModuleException ex) {
				throw ex;
			} catch (TypeStorageException ex) {
				throw ex;
			} catch (SpecParseException ex) {
				throw ex;
			} catch (Exception ex) {
				throw new SpecParseException("Unexpected error during spec-file parsing: " + ex.getMessage(), ex);			
			} finally {
				try {
					if (transactionStartTime > 0) {
						rollbackModuleTransaction(moduleName, transactionStartTime);
					}
				} catch (Exception ignore) {}
			}
		} finally {
			releaseWriteLock(moduleName);
		}
	}
	
	private Change findTypeChange(ModuleInfo info, KbTypedef newType) 
			throws SpecParseException, NoSuchTypeException, NoSuchModuleException, TypeStorageException {
		if (!info.getTypes().containsKey(newType.getName()))
			return Change.notCompatible;
		TypeInfo ti = info.getTypes().get(newType.getName());
		KbTypedef oldType = getTypeParsingDocumentNL(new TypeDefId(info.getModuleName() + "." + ti.getTypeName(), 
				ti.getTypeVersion()));
		return findChange(oldType, newType);
	}
	
	private Change findChange(KbType oldType, KbType newType) throws SpecParseException {
		if (!oldType.getClass().equals(newType.getClass()))
			return Change.notCompatible;
		if (newType instanceof KbTypedef) {
			KbTypedef oldIType = (KbTypedef)oldType;
			KbTypedef newIType = (KbTypedef)newType;
			if (!newIType.getName().equals(oldIType.getName()))
				return Change.notCompatible;
			return findChange(oldIType.getAliasType(), newIType.getAliasType());
		} else if (newType instanceof KbList) {
			KbList oldIType = (KbList)oldType;
			KbList newIType = (KbList)newType;
			return findChange(oldIType.getElementType(), newIType.getElementType());
		} else if (newType instanceof KbMapping) {
			KbMapping oldIType = (KbMapping)oldType;
			KbMapping newIType = (KbMapping)newType;
			return findChange(oldIType.getValueType(), newIType.getValueType());
		} else if (newType instanceof KbTuple) {
			KbTuple oldIType = (KbTuple)oldType;
			KbTuple newIType = (KbTuple)newType;
			if (oldIType.getElementTypes().size() != newIType.getElementTypes().size())
				return Change.notCompatible;
			Change ret = Change.noChange;
			for (int pos = 0; pos < oldIType.getElementTypes().size(); pos++) {
				ret = Change.joinChanges(ret, findChange(oldIType.getElementTypes().get(pos), 
						newIType.getElementTypes().get(pos)));
				if (ret == Change.notCompatible)
					return ret;
			}
			return ret;
		} else if (newType instanceof KbUnspecifiedObject) {
			return Change.noChange;
		} else if (newType instanceof KbScalar) {
			KbScalar oldIType = (KbScalar)oldType;
			KbScalar newIType = (KbScalar)newType;
			if (oldIType.getScalarType() != newIType.getScalarType())
				return Change.notCompatible;
			String oldIdRefText = "" + oldIType.getIdReference();
			String newIdRefText = "" + newIType.getIdReference();
			return oldIdRefText.equals(newIdRefText) ? Change.noChange : Change.notCompatible;
		} else if (newType instanceof KbStruct) {
			KbStruct oldIType = (KbStruct)oldType;
			KbStruct newIType = (KbStruct)newType;
			Map<String, KbStructItem> newFields = new HashMap<String, KbStructItem>();
			for (KbStructItem item : newIType.getItems())
				newFields.put(item.getName(), item);
			Change ret = Change.noChange;
			for (KbStructItem oldItem : oldIType.getItems()) {
				if (!newFields.containsKey(oldItem.getName()))
					return Change.notCompatible;
				ret = Change.joinChanges(ret, findChange(oldItem.getItemType(), 
						newFields.get(oldItem.getName()).getItemType()));
				if (ret == Change.notCompatible)
					return ret;
				if (oldItem.isOptional() != newFields.get(oldItem.getName()).isOptional())
					return Change.notCompatible;
				newFields.remove(oldItem.getName());
			}
			for (KbStructItem newItem : newFields.values()) {
				if (!newItem.isOptional())
					return Change.notCompatible;
				ret = Change.joinChanges(ret, Change.backwardCompatible);
			}
			return ret;
		}
		throw new SpecParseException("Unknown type class: " + newType.getClass().getSimpleName());
	}

	private Change findFuncChange(ModuleInfo info, KbFuncdef newFunc) 
			throws NoSuchFuncException, NoSuchModuleException, TypeStorageException, SpecParseException {
		if (!info.getFuncs().containsKey(newFunc.getName())) {
			return Change.notCompatible;
		}
		FuncInfo fi = info.getFuncs().get(newFunc.getName());
		KbFuncdef oldFunc = getFuncParsingDocumentNL(info.getModuleName(), fi.getFuncName(), fi.getFuncVersion());
		if (oldFunc.getParameters().size() != newFunc.getParameters().size() ||
				oldFunc.getReturnType().size() != newFunc.getReturnType().size()) {
			return Change.notCompatible;
		}
		Change ret = Change.noChange;
		for (int pos = 0; pos < oldFunc.getParameters().size(); pos++) {
			KbParameter oldParam = oldFunc.getParameters().get(pos);
			KbParameter newParam = newFunc.getParameters().get(pos);
			ret = Change.joinChanges(ret, findChange(oldParam.getType(), newParam.getType()));
			if (ret == Change.notCompatible)
				return ret;
		}
		for (int pos = 0; pos < oldFunc.getReturnType().size(); pos++) {
			KbParameter oldRet = oldFunc.getReturnType().get(pos);
			KbParameter newRet = newFunc.getReturnType().get(pos);
			ret = Change.joinChanges(ret, findChange(oldRet.getType(), newRet.getType()));
			if (ret == Change.notCompatible)
				return ret;
		}
		return ret;
	}

	private Set<RefInfo> extractTypeRefs(KbTypedef main, Map<String, ModuleInfo> moduleToInfo,
			Set<String> mainRegisteredTypes) throws SpecParseException {
		Set<RefInfo> ret = new TreeSet<RefInfo>();
		collectTypeRefs(ret, main.getModule(), main.getName(), main.getAliasType(), moduleToInfo, mainRegisteredTypes);
		return ret;
	}

	private Set<RefInfo> extractTypeRefs(String module, String funcName, KbParameter main, 
			Map<String, ModuleInfo> moduleToInfo, Set<String> mainRegisteredTypes)
					throws SpecParseException {
		Set<RefInfo> ret = new TreeSet<RefInfo>();
		collectTypeRefs(ret, module, funcName, main.getType(), moduleToInfo, mainRegisteredTypes);
		return ret;
	}

	private void collectTypeRefs(Set<RefInfo> ret, String mainModule, String mainName, KbType internal, 
			Map<String, ModuleInfo> moduleToInfo, Set<String> mainRegisteredTypes) 
					throws SpecParseException {
		if (internal instanceof KbTypedef) {
			KbTypedef type = (KbTypedef)internal;
			boolean isOuterModule = !type.getModule().equals(mainModule);
			boolean terminal = isOuterModule || mainRegisteredTypes.contains(type.getName());
			if (terminal) {
				RefInfo ref = new RefInfo();
				ref.setDepModule(mainModule);
				ref.setDepName(mainName);
				ref.setRefModule(type.getModule());
				ref.setRefName(type.getName());
				if (isOuterModule) {
					ModuleInfo oModule = moduleToInfo.get(type.getModule());
					TypeInfo oType = null;
					if (oModule != null)
						oType = oModule.getTypes().get(type.getName());
					if (oType == null)
						throw new SpecParseException("Reference to external not registered " +
								"module/type is missing: " + type.getModule() + "." + type.getName());
					ref.setRefVersion(oType.getTypeVersion());
				}
				ret.add(ref);
			} else {
				collectTypeRefs(ret, mainModule, mainName, type.getAliasType(), moduleToInfo, mainRegisteredTypes);
			}
		} else if (internal instanceof KbList) {
			KbList type = (KbList)internal;
			collectTypeRefs(ret, mainModule, mainName, type.getElementType(), moduleToInfo, mainRegisteredTypes);
		} else if (internal instanceof KbMapping) {
			KbMapping type = (KbMapping)internal;
			collectTypeRefs(ret, mainModule, mainName, type.getValueType(), moduleToInfo, mainRegisteredTypes);
		} else if (internal instanceof KbStruct) {
			KbStruct type = (KbStruct)internal;
			for (KbStructItem item : type.getItems())
				collectTypeRefs(ret, mainModule, mainName, item.getItemType(), moduleToInfo, mainRegisteredTypes);				
		} else if (internal instanceof KbTuple) {
			KbTuple type = (KbTuple)internal;
			for (KbType iType : type.getElementTypes())
				collectTypeRefs(ret, mainModule, mainName, iType, moduleToInfo, mainRegisteredTypes);				
		}
	}
	
	private void saveIncludedModuleRecusive(File workDir, IncludeDependentPath parent, 
			String moduleName, long version, Map<String, IncludeDependentPath> savedModules, 
			Map<String, Long> moduleVersionRestrictions, StaticIncludeProvider sip) 
			throws NoSuchModuleException, IOException, TypeStorageException, SpecParseException {
		ModuleInfo info = getModuleInfoNL(moduleName, version);
		IncludeDependentPath currentPath = new IncludeDependentPath(info, parent);
		Long restriction = moduleVersionRestrictions.get(moduleName);
		if (restriction != null && version != restriction) 
			throw new SpecParseException("Version of dependent module " + currentPath + " " +
					"is not compatible with module version restriction: " + restriction);
		if (savedModules.containsKey(moduleName)) {
			IncludeDependentPath alreadyPath = savedModules.get(moduleName);
			if (alreadyPath.info.getVersionTime() != currentPath.info.getVersionTime())
				throw new SpecParseException("Incompatible module dependecies: " + alreadyPath + 
						" and " + currentPath);
			return;
		}
		String spec = getModuleSpecDocument(moduleName, version);
		if (workDir != null)
			writeFile(spec, new File(workDir, moduleName + ".types"));
		if (sip != null)
			sip.addSpecFile(moduleName, spec);
		savedModules.put(moduleName, currentPath);
		for (Map.Entry<String, Long> entry : info.getIncludedModuleNameToVersion().entrySet()) {
			String includedModule = entry.getKey();
			long includedVersion = entry.getValue();
			saveIncludedModuleRecusive(workDir, currentPath, includedModule, includedVersion, 
					savedModules, moduleVersionRestrictions, sip);
		}
	}
	
	private static void writeFile(String text, File f) throws IOException {
		FileWriter fw = new FileWriter(f);
		fw.write(text);
		fw.close();
	}
	
	private void deleteTempDir(File dir) {
		for (File f : dir.listFiles()) {
			if (f.isFile()) {
				f.delete();
			} else {
				deleteTempDir(f);
			}
		}
		dir.delete();
	}
		
	public void addOwnerToModule(String knownOwnerUserId, String moduleName, String newOwnerUserId, 
			boolean withChangeOwnersPrivilege) throws TypeStorageException, NoSuchPrivilegeException {
		checkUserCanChangePrivileges(knownOwnerUserId, moduleName);
		storage.addOwnerToModule(moduleName, newOwnerUserId, withChangeOwnersPrivilege);
	}

	public void removeOwnerFromModule(String knownOwnerUserId, String moduleName, String removedOwnerUserId) 
			throws NoSuchPrivilegeException, TypeStorageException {
		checkUserCanChangePrivileges(knownOwnerUserId, moduleName);
		storage.removeOwnerFromModule(moduleName, removedOwnerUserId);
	}
	
	private void checkUserCanChangePrivileges(String knownOwnerUserId,
			String moduleName) throws NoSuchPrivilegeException, TypeStorageException {
		boolean canChangeOwnersPrivilege = checkUserIsOwnerOrAdmin(moduleName, knownOwnerUserId);
		if (!canChangeOwnersPrivilege)
			throw new NoSuchPrivilegeException("User " + knownOwnerUserId + " can not change " +
					"priviledges for module " + moduleName);
	}
	
	public String getModuleDescription(String moduleName) 
			throws TypeStorageException, NoSuchModuleException {
		return getModuleInfo(moduleName).getDescription();
	}

	public String getModuleDescription(String moduleName, long version) 
			throws TypeStorageException, NoSuchModuleException {
		return getModuleInfo(moduleName, version).getDescription();
	}

	public String getModuleDescription(ModuleDefId moduleDef) 
			throws TypeStorageException, NoSuchModuleException {
		return getModuleInfo(moduleDef).getDescription();
	}

	public String getTypeDescription(TypeDefId typeDef) 
			throws NoSuchTypeException, NoSuchModuleException, TypeStorageException {
		return getTypeParsingDocument(typeDef).getComment();
	}
	
	public String getFuncDescription(String moduleName, String funcName, String version) 
			throws NoSuchFuncException, NoSuchModuleException, TypeStorageException {
		return getFuncParsingDocument(moduleName, funcName, version).getComment();
	}

	public String getModuleMD5(String moduleName) 
			throws NoSuchModuleException, TypeStorageException {
		return getModuleInfo(moduleName).getMd5hash();
	}

	public String getModuleMD5(String moduleName, long version) 
			throws TypeStorageException, NoSuchModuleException {
		return getModuleInfo(moduleName, version).getMd5hash();
	}
	
	public String getModuleMD5(ModuleDefId moduleDef) 
			throws NoSuchModuleException, TypeStorageException {
		return getModuleInfo(moduleDef).getMd5hash();
	}
	
	public Set<ModuleDefId> findModuleVersionsByMD5(String moduleName, String md5) 
			throws NoSuchModuleException, TypeStorageException {
		requestReadLock(moduleName);
		try {
			Set<ModuleDefId> ret = new LinkedHashSet<ModuleDefId>();
			for (long version : getAllModuleVersionsNL(moduleName, false)) {
				ModuleInfo info = getModuleInfoNL(moduleName, version);
				if (md5.equals(info.getMd5hash()))
					ret.add(new ModuleDefId(moduleName, version));
			}
			return ret;
		} finally {
			releaseReadLock(moduleName);
		}
	}
	
	public List<ModuleDefId> findModuleVersionsByTypeVersion(TypeDefId typeDef) 
			throws NoSuchModuleException, TypeStorageException, NoSuchTypeException {
		String moduleName = typeDef.getType().getModule();
		requestReadLock(moduleName);
		try {
			boolean withUnreleased = typeDef.isAbsolute();
			typeDef = resolveTypeDefIdNL(typeDef);
			List<ModuleDefId> ret = new ArrayList<ModuleDefId>();
			Map<Long, Boolean> moduleVersions = storage.getModuleVersionsForTypeVersion(moduleName, 
					typeDef.getType().getName(), typeDef.getVerString());
			if (withUnreleased) {
				for (boolean isReleased : moduleVersions.values()) 
					if (isReleased) {
						withUnreleased = false;
						break;
					}
			}
			for (long moduleVersion : moduleVersions.keySet()) 
				if (withUnreleased || moduleVersions.get(moduleVersion))
					ret.add(new ModuleDefId(moduleName, moduleVersion));
			return ret;
		} finally {
			releaseReadLock(moduleName);
		}
	}

	public List<String> getModulesByOwner(String userId) throws TypeStorageException {
		return filterNotsupportedModules(storage.getModulesForOwner(userId).keySet());
	}

	private List<String> filterNotsupportedModules(Collection<String> input) throws TypeStorageException {
		List<String> ret = new ArrayList<String>();
		Set<String> supported = new HashSet<String>(storage.getAllRegisteredModules(false));
		for (String mod : input)
			if (supported.contains(mod))
				ret.add(mod);
		return ret;
	}
	
	private void checkModuleSupported(String moduleName) 
			throws TypeStorageException, NoSuchModuleException {
		if (!isModuleSupported(moduleName))
			throw new NoSuchModuleException("Module " + moduleName + " is no longer supported");
	}
	
	private boolean isModuleSupported(String moduleName) throws TypeStorageException {
		return storage.getModuleSupportedState(moduleName);
	}
	
	public void stopModuleSupport(String moduleName, String userId) 
			throws NoSuchModuleException, TypeStorageException, NoSuchPrivilegeException {
		checkModuleRegistered(moduleName);
		checkAdmin(userId);
		requestWriteLock(moduleName);
		try {
			storage.changeModuleSupportedState(moduleName, false);
		} finally {
			releaseWriteLock(moduleName);
		}
	}
	
	public void resumeModuleSupport(String moduleName, String userId)
			throws NoSuchModuleException, TypeStorageException, NoSuchPrivilegeException {
		checkModuleRegistered(moduleName);
		checkAdmin(userId);
		requestWriteLock(moduleName);
		try {
			storage.changeModuleSupportedState(moduleName, true);
		} finally {
			releaseWriteLock(moduleName);
		}
	}
	
	private static class ComponentChange {
		boolean isType;
		boolean isDeletion;
		String name;
		String jsonSchemaDocument;
		KbTypedef typeParsing;
		KbFuncdef funcParsing;
		boolean notBackwardCompatible;
		Set<RefInfo> dependencies;
		
		public ComponentChange(boolean isType, boolean isDeletion, String name, 
				String jsonSchemaDocument, KbTypedef typeParsing, KbFuncdef funcParsing, 
				boolean notBackwardCompatible, Set<RefInfo> dependencies) {
			this.isType = isType;
			this.isDeletion = isDeletion;
			this.name = name;
			this.jsonSchemaDocument = jsonSchemaDocument;
			this.typeParsing = typeParsing;
			this.funcParsing = funcParsing;
			this.notBackwardCompatible = notBackwardCompatible;
			this.dependencies = dependencies;
		}
	}
	
	private static class IncludeDependentPath {
		ModuleInfo info;
		IncludeDependentPath parent;

		public IncludeDependentPath() {
			info = new ModuleInfo();
			info.setModuleName("RootModule");
		}
		
		public IncludeDependentPath(ModuleInfo info, IncludeDependentPath parent) {
			this.info = info;
			this.parent = parent;
		}

		@Override
		public String toString() {
			StringBuilder ret = new StringBuilder();
			for (IncludeDependentPath cur = this; cur != null; cur = cur.parent) {
				if (ret.length() > 0)
					ret.append("<-");
				ret.append(cur.info.getModuleName());
				if (cur.info.getVersionTime() > 0)
					ret.append('(').append(cur.info.getVersionTime()).append(')');
			}
			return ret.toString();
		}
	}
	
	private static class ModuleState {
		int readerCount = 0;
		int writerCount = 0;
	}
}
