package us.kbase.workspace.test.kbase;

import static us.kbase.workspace.test.kbase.JSONRPCLayerTester.administerCommand;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.ini4j.Ini;
import org.ini4j.Profile.Section;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.mongodb.DB;
import com.mongodb.MongoClient;

import us.kbase.abstracthandle.AbstractHandleClient;
import us.kbase.abstracthandle.Handle;
import us.kbase.auth.AuthService;
import us.kbase.common.mongo.exceptions.InvalidHostException;
import us.kbase.common.service.UObject;
import us.kbase.common.service.UnauthorizedException;
import us.kbase.common.test.TestException;
import us.kbase.shock.client.BasicShockClient;
import us.kbase.shock.client.ShockNodeId;
import us.kbase.workspace.CreateWorkspaceParams;
import us.kbase.workspace.ObjectIdentity;
import us.kbase.workspace.ObjectSaveData;
import us.kbase.workspace.RegisterTypespecParams;
import us.kbase.workspace.SaveObjectsParams;
import us.kbase.workspace.SetPermissionsParams;
import us.kbase.workspace.WorkspaceClient;
import us.kbase.workspace.WorkspaceServer;
import us.kbase.workspace.test.WorkspaceTestCommon;
import us.kbase.workspace.test.controllers.handle.HandleServiceController;
import us.kbase.workspace.test.controllers.mongo.MongoController;
import us.kbase.workspace.test.controllers.mysql.MySQLController;
import us.kbase.workspace.test.controllers.shock.ShockController;

public class JSONRPCLayerHandleTest {

	private static MySQLController MYSQL;
	private static MongoController MONGO;
	private static ShockController SHOCK;
	private static HandleServiceController HANDLE;
	private static WorkspaceServer SERVER;
	
	private static String USER1;
	private static String USER2;
	private static WorkspaceClient CLIENT1;
	private static WorkspaceClient CLIENT2;

	private static AbstractHandleClient HANDLE_CLIENT;
	
	private static String HANDLE_TYPE = "HandleList.HList-0.1";
	
	@BeforeClass
	public static void setUpClass() throws Exception {
		USER1 = System.getProperty("test.user1");
		USER2 = System.getProperty("test.user2");
		String u3 = System.getProperty("test.user3");
		if (USER1.equals(USER2)) {
			throw new TestException("All the test users must be unique: " + 
					StringUtils.join(Arrays.asList(USER1, USER2, u3), " "));
		}
		if (USER1.equals(u3)) {
			throw new TestException("All the test users must be unique: " + 
					StringUtils.join(Arrays.asList(USER1, USER2, u3), " "));
		}
		if (USER2.equals(u3)) {
			throw new TestException("All the test users must be unique: " + 
					StringUtils.join(Arrays.asList(USER1, USER2, u3), " "));
		}
		String p1 = System.getProperty("test.pwd1");
		String p2 = System.getProperty("test.pwd2");
		String p3 = System.getProperty("test.pwd3");
		
		try {
			AuthService.login(u3, p3);
		} catch (Exception e) {
			throw new TestException("Could not log in test user test.user3: " + u3, e);
		}
		
		WorkspaceTestCommon.stfuLoggers();
		
		MONGO = new MongoController(WorkspaceTestCommon.getMongoExe(),
				Paths.get(WorkspaceTestCommon.getTempDir()));
		System.out.println("Using Mongo temp dir " + MONGO.getTempDir());
		final String mongohost = "localhost:" + MONGO.getServerPort();
		MongoClient mongoClient = new MongoClient(mongohost);

		SHOCK = new ShockController(
				WorkspaceTestCommon.getShockExe(),
				Paths.get(WorkspaceTestCommon.getTempDir()),
				u3,
				mongohost,
				"JSONRPCLayerHandleTest_ShockDB",
				"foo",
				"foo");
		System.out.println("Using Shock temp dir " + SHOCK.getTempDir());

		MYSQL = new MySQLController(
				WorkspaceTestCommon.getMySQLExe(),
				WorkspaceTestCommon.getMySQLInstallExe(),
				Paths.get(WorkspaceTestCommon.getTempDir()));
		System.out.println("Using MySQL temp dir " + MYSQL.getTempDir());
		
		HANDLE = new HandleServiceController(
				WorkspaceTestCommon.getPlackupExe(),
				WorkspaceTestCommon.getHandleServicePSGI(),
				WorkspaceTestCommon.getHandleManagerPSGI(),
				u3,
				MYSQL,
				"http://localhost:" + SHOCK.getServerPort(),
				u3,
				p3,
				WorkspaceTestCommon.getHandlePERL5LIB(),
				Paths.get(WorkspaceTestCommon.getTempDir()));
		System.out.println("Using Handle Service temp dir " +
				HANDLE.getTempDir());
		
		
		SERVER = startupWorkspaceServer(mongohost,
				mongoClient.getDB("JSONRPCLayerHandleTester"), 
				"JSONRPCLayerHandleTester_types",
				u3, p3);
		int port = SERVER.getServerPort();
		System.out.println("Started test workspace server on port " + port);
		try {
			CLIENT1 = new WorkspaceClient(new URL("http://localhost:" + port), USER1, p1);
		} catch (UnauthorizedException ue) {
			throw new TestException("Unable to login with test.user1: " + USER1 +
					"\nPlease check the credentials in the test configuration.", ue);
		}
		try {
			CLIENT2 = new WorkspaceClient(new URL("http://localhost:" + port), USER2, p2);
		} catch (UnauthorizedException ue) {
			throw new TestException("Unable to login with test.user2: " + USER2 +
					"\nPlease check the credentials in the test configuration.", ue);
		}
		CLIENT1.setIsInsecureHttpConnectionAllowed(true);
		CLIENT2.setIsInsecureHttpConnectionAllowed(true);
		
		setUpSpecs();
		
		HANDLE_CLIENT = new AbstractHandleClient(new URL("http://localhost:" +
				HANDLE.getHandleServerPort()), USER1, p1);
		HANDLE_CLIENT.setIsInsecureHttpConnectionAllowed(true);
	}

	private static void setUpSpecs() throws Exception {
		final String handlespec =
				"module HandleList {" +
					"/* @id handle */" +
					"typedef string handle;" +
					"typedef structure {" +
						"list<handle> handles;" +
					"} HList;" +
				"};";
		CLIENT1.requestModuleOwnership("HandleList");
		administerCommand(CLIENT2, "approveModRequest", "module", "HandleList");
		CLIENT1.registerTypespec(new RegisterTypespecParams()
			.withDryrun(0L)
			.withSpec(handlespec)
			.withNewTypes(Arrays.asList("HList")));
	}
	
	private static WorkspaceServer startupWorkspaceServer(
			String mongohost,
			DB db,
			String typedb,
			String handleUser,
			String handlePwd)
			throws InvalidHostException, UnknownHostException, IOException,
			NoSuchFieldException, IllegalAccessException, Exception,
			InterruptedException {
		
		WorkspaceTestCommon.initializeGridFSWorkspaceDB(db, typedb);

		//write the server config file:
		File iniFile = File.createTempFile("test", ".cfg",
				new File(WorkspaceTestCommon.getTempDir()));
		if (iniFile.exists()) {
			iniFile.delete();
		}
		System.out.println("Created temporary config file: " +
				iniFile.getAbsolutePath());
		Ini ini = new Ini();
		Section ws = ini.add("Workspace");
		ws.add("mongodb-host", mongohost);
		ws.add("mongodb-database", db.getName());
		ws.add("backend-secret", "foo");
		ws.add("handle-service-url", "http://localhost:" +
				HANDLE.getHandleServerPort());
		ws.add("handle-manager-url", "http://localhost:" +
				HANDLE.getHandleManagerPort());
		ws.add("handle-manager-user", handleUser);
		ws.add("handle-manager-pwd", handlePwd);
		ws.add("ws-admin", USER2);
		ws.add("temp-dir", Paths.get(WorkspaceTestCommon.getTempDir())
				.resolve("tempForJSONRPCLayerTester"));
		ini.store(iniFile);
		iniFile.deleteOnExit();

		//set up env
		Map<String, String> env = JSONRPCLayerTester.getenv();
		env.put("KB_DEPLOYMENT_CONFIG", iniFile.getAbsolutePath());
		env.put("KB_SERVICE_NAME", "Workspace");

		WorkspaceServer.clearConfigForTests();
		WorkspaceServer server = new WorkspaceServer();
		new JSONRPCLayerTester.ServerThread(server).start();
		System.out.println("Main thread waiting for server to start up");
		while (server.getServerPort() == null) {
			Thread.sleep(1000);
		}
		return server;
	}

	@Test
	public void basicHandleTest() throws Exception {
		String workspace = "basichandle";
		CLIENT1.createWorkspace(new CreateWorkspaceParams().withWorkspace(workspace));
		Handle h1 = HANDLE_CLIENT.newHandle();
		List<String> handleList = new LinkedList<String>();
		handleList.add(h1.getHid());
		Map<String, Object> handleobj = new HashMap<String, Object>();
		handleobj.put("handles", handleList);
		CLIENT1.saveObjects(new SaveObjectsParams().withWorkspace(workspace)
				.withObjects(Arrays.asList(
						new ObjectSaveData().withData(new UObject(handleobj))
						.withType(HANDLE_TYPE))));
		//TODO fail save with CLIENT2
		//TODO fail get node with CLIENT2
		
		CLIENT1.setPermissions(new SetPermissionsParams().withWorkspace(workspace)
				.withUsers(Arrays.asList(USER2)).withNewPermission("r"));
		
		CLIENT2.getObjects(Arrays.asList(new ObjectIdentity().withWorkspace(workspace)
				.withObjid(1L)));
		BasicShockClient bsc = new BasicShockClient(
				new URL("http://localhost:" + SHOCK.getServerPort()), CLIENT2.getToken());
		bsc.getNode(new ShockNodeId(h1.getId()));
		//TODO test with 3 other methods
	}

	@AfterClass
	public static void tearDownClass() throws Exception {
		if (SERVER != null) {
			System.out.print("Killing workspace server... ");
			SERVER.stopServer();
			System.out.println("Done");
		}
		if (HANDLE != null) {
			HANDLE.destroy(WorkspaceTestCommon.getDeleteTempFiles());
		}
		if (SHOCK != null) {
			SHOCK.destroy(WorkspaceTestCommon.getDeleteTempFiles());
		}
		if (MONGO != null) {
			MONGO.destroy(WorkspaceTestCommon.getDeleteTempFiles());
		}
		if (MYSQL != null) {
			MYSQL.destroy(WorkspaceTestCommon.getDeleteTempFiles());
		}
	}
}
