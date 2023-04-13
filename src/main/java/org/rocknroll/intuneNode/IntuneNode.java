package org.rocknroll.intuneNode;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.ResourceBundle;

import javax.inject.Inject;

import org.apache.http.HttpEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.forgerock.json.JsonValue;
import org.forgerock.openam.annotations.sm.Attribute;
import org.forgerock.openam.auth.node.api.Action;
import org.forgerock.openam.auth.node.api.Node;
import org.forgerock.openam.auth.node.api.NodeProcessException;
import org.forgerock.openam.auth.node.api.NodeState;
import org.forgerock.openam.auth.node.api.TreeContext;
import org.forgerock.openam.core.CoreWrapper;
import org.forgerock.openam.core.realms.Realm;
import org.forgerock.openam.sm.annotations.adapters.Password;
import org.forgerock.util.i18n.PreferredLocales;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.inject.assistedinject.Assisted;
import com.sun.identity.sm.RequiredValueValidator;

@Node.Metadata(outcomeProvider = IntuneNode.IntuneOutcomeProvider.class, configClass = IntuneNode.Config.class, tags = {"marketplace", "trustnetwork"})
public class IntuneNode implements Node {

	private final Logger logger = LoggerFactory.getLogger(IntuneNode.class);
	private final Config config;
	private static final String BUNDLE = "org/rocknroll/intuneNode/IntuneNode";
	private static final String loggerPrefix = "[Microsoft Intune][Marketplace] ";
	private static final String IntuneDeviceURL = "https://graph.microsoft.com/v1.0/deviceManagement/manageddevices/";
	
	//TODO As of today 4/13/2023 - no relationship exist for v1.0 from a device to it's apps - https://learn.microsoft.com/en-us/graph/api/resources/intune-devices-manageddevice?view=graph-rest-1.0
	private static final String IntuneDeviceURLForApps = "https://graph.microsoft.com/beta/deviceManagement/manageddevices/";


	/**
	 * Configuration for the node.
	 */
	public interface Config {
		@Attribute(order = 100)
		default String inTuneHeader() {
			return "x-intune";
		}

		@Attribute(order = 200)
		default boolean inTuneSS() {
			return false;
		}

		@Attribute(order = 300, validators = { RequiredValueValidator.class })
		String azureTenantId();

		@Attribute(order = 400, validators = { RequiredValueValidator.class })
		String appRegistrationClientId();

		@Attribute(order = 500, validators = { RequiredValueValidator.class })
		@Password
		char[] appRegistrationClientSecret();

		@Attribute(order = 600, validators = { RequiredValueValidator.class })
		String userName();

		@Attribute(order = 700, validators = { RequiredValueValidator.class })
		@Password
		char[] userPassword();

		@Attribute(order = 800)
		default boolean passDeviceInfoSession() {
			return false;
		}

		@Attribute(order = 900)
		default boolean extractApps() {
			return false;
		}

	}

	/**
	 * Create the node using Guice injection. Just-in-time bindings can be used to
	 * obtain instances of other classes from the plugin.
	 *
	 * @param config The service config.
	 * @param realm  The realm the node is in.
	 * @throws NodeProcessException If the configuration was not valid.
	 */
	@Inject
	public IntuneNode(@Assisted Config config, CoreWrapper coreWrapper, @Assisted Realm realm)
			throws NodeProcessException {
		this.config = config;
	}

	@Override
	public Action process(TreeContext context) throws NodeProcessException {
		logger.warn(loggerPrefix + "Intune Node Started");
		try {
			Action.ActionBuilder action;

			NodeState ns = context.getStateFor(this);
			String deviceId = null;

			if (config.inTuneSS()) {
				if (ns!=null && ns.get(config.inTuneHeader())!=null && ns.get(config.inTuneHeader()).asString().length()>0)
				deviceId = ns.get(config.inTuneHeader()).asString().replaceAll("CN=", "");
			}
			else {
				if(context!=null && context.request!=null && context.request.headers!=null && context.request.headers.get(config.inTuneHeader())!=null)
				deviceId = context.request.headers.get(config.inTuneHeader()).get(0).replaceAll("CN=", "");
			}

			if (deviceId != null) {
				logger.warn(loggerPrefix + ": deviceID: " + deviceId);
				//Date startATGet = new Date();
				String access_token = roGrant();
				//Date endATGet = new Date();
				
				String complianceResult = checkCompliance(ns, access_token, deviceId);
				//Date afterComplianceDate = new Date();
				/**
				 * If app extraction is wanted then extract apps
				 */

				if (config.extractApps())
					extractApps(ns, access_token, deviceId);
				
				//Date afterAppExt = new Date();
				
				//ns.putShared(loggerPrefix.trim() + "ATTime","To get the access Token time: " + (endATGet.getTime()-startATGet.getTime()));
				//ns.putShared(loggerPrefix.trim() + "CompTime","To get the compliance check time: " + (afterComplianceDate.getTime()-endATGet.getTime()));
				//ns.putShared(loggerPrefix.trim() + "AppExtTime","To get the wxtract check time: " + (afterAppExt.getTime()-afterComplianceDate.getTime()));

				/**
				 * Evaluate device compliance check results to return certain outcome.
				 */
				switch (complianceResult) {
				case "compliant":
					logger.warn(loggerPrefix + ": Device Compliance: compliant");
					action = goTo(IntuneNodeOutcome.COMPLIANT);
					break;
				case "noncompliant":
					logger.warn(loggerPrefix + ": Device Compliance: non compliant");
					action = goTo(IntuneNodeOutcome.NONCOMPLIANT);
					break;
				case "inGracePeriod":
					logger.warn(loggerPrefix + ": Device Compliance: inGracePeriod");
					action = goTo(IntuneNodeOutcome.INGRACEPERIOD);
					break;
				case "conflict":
					logger.warn(loggerPrefix + ": Device Compliance: conflict");
					action = goTo(IntuneNodeOutcome.CONFLICT);
					break;
				case "error":
					logger.warn(loggerPrefix + ": Device Compliance: Error");
					action = goTo(IntuneNodeOutcome.ERROR);
					break;
				case "configManager":
					logger.warn(loggerPrefix + ": Device Compliance: configManager");
					action = goTo(IntuneNodeOutcome.CONFIGMANAGER);
					break;
				case "unknown":
				default:
					logger.warn(loggerPrefix + ": Device Compliance: unknown");
					action = goTo(IntuneNodeOutcome.UNKNOWN);
					break;
				}
			} else {
				logger.warn(loggerPrefix + ": No device ID found: ");
				action = goTo(IntuneNodeOutcome.NOID);
			}
			return action.build();

		} catch (Exception ex) {
			logger.error(loggerPrefix + "Exception occurred: " + ex.getStackTrace());
			context.getStateFor(this).putShared(loggerPrefix + "Exception", new Date() + ": " + ex.getMessage());
			StringWriter sw = new StringWriter();
			PrintWriter pw = new PrintWriter(sw);
			ex.printStackTrace(pw);
			context.getStateFor(this).putShared(loggerPrefix + "StackTracke", new Date() + ": " + sw.toString());
			return Action.goTo(IntuneNodeOutcome.ERROR.name()).build();
		}

	}

	private Action.ActionBuilder goTo(IntuneNodeOutcome outcome) {
		return Action.goTo(outcome.name());
	}

	public enum IntuneNodeOutcome {
		NOID, COMPLIANT, NONCOMPLIANT, INGRACEPERIOD, ERROR, CONFLICT, CONFIGMANAGER, UNKNOWN, OTHER
	}

	private String roGrant() throws Exception {
		HttpClient httpClient = HttpClientBuilder.create().build();
		HttpPost post = new HttpPost(
				"https://login.microsoftonline.com/" + config.azureTenantId() + "/oauth2/v2.0/token");

		List<NameValuePair> nameValuePairs = new ArrayList<NameValuePair>(1);
		nameValuePairs.add(new BasicNameValuePair("client_id", config.appRegistrationClientId()));
		nameValuePairs.add(new BasicNameValuePair("client_secret", charToString(config.appRegistrationClientSecret())));
		nameValuePairs.add(new BasicNameValuePair("grant_type", "password"));
		nameValuePairs.add(new BasicNameValuePair("username", config.userName()));
		nameValuePairs.add(new BasicNameValuePair("password", charToString(config.userPassword())));
		nameValuePairs.add(new BasicNameValuePair("scope", "DeviceManagementManagedDevices.Read.All"));

		post.setEntity(new UrlEncodedFormEntity(nameValuePairs));
		HttpResponse response = httpClient.execute(post);

		HttpEntity entity = response.getEntity();
		String content = EntityUtils.toString(entity);

		JSONObject jsonObject = new JSONObject(content);
		return jsonObject.getString("access_token");
	}

	private String checkCompliance(NodeState ns, String access_token, String deviceId) throws Exception {
		HttpClient httpClient = HttpClientBuilder.create().build();

		HttpGet request = new HttpGet(IntuneDeviceURL + deviceId);
		String bearerHeader = "Bearer " + access_token;
		request.setHeader(HttpHeaders.AUTHORIZATION, bearerHeader);
		HttpResponse response = httpClient.execute(request);
		HttpEntity entity = response.getEntity();
		String content = EntityUtils.toString(entity);

		JSONObject jsonObject = new JSONObject(content);

		/**
		 * Extract some of device properties returned by Intune.
		 */
		if (config.passDeviceInfoSession()) {
			for (Iterator<String> theKeys = jsonObject.keys(); theKeys.hasNext();) {
				String thisKey = theKeys.next();
				Object thisVal = jsonObject.get(thisKey);
				String thisClassName = thisVal.getClass().getName();

				switch (thisClassName) {

				case "java.lang.String":
					String theValStr = jsonObject.getString(thisKey);
					if(theValStr.trim().length()>0)
						ns.putShared("INTUNE_" + thisKey, theValStr);
					break;
				case "org.json.JSONArray":
					ns.putShared("INTUNE_" + thisKey, jsonObject.getJSONArray(thisKey).toString());
					break;
				case "java.lang.Integer":
					ns.putShared("INTUNE_" + thisKey, Integer.toString(jsonObject.getInt(thisKey)));
					break;
				case "java.lang.Long":
					ns.putShared("INTUNE_" + thisKey, Long.toString(jsonObject.getLong(thisKey)));
					break;
				case "java.lang.Boolean":
					ns.putShared("INTUNE_" + thisKey, Boolean.toString(jsonObject.getBoolean(thisKey)));
					break;
				case "org.json.JSONObject":
					ns.putShared("INTUNE_" + thisKey, jsonObject.getJSONObject(thisKey).toString());
					break;
				case "org.json.JSONObject$Null":
				default:
					break;
				}
			}
		}

		String intuneComplianceState = jsonObject.getString("complianceState");

		return intuneComplianceState;
	}

	private void extractApps(NodeState ns, String access_token, String deviceId) throws Exception {
		HttpClient httpClient = HttpClientBuilder.create().build();

		HttpGet request = new HttpGet(IntuneDeviceURLForApps + deviceId + "/detectedApps");
		String bearerHeader = "Bearer " + access_token;
		request.setHeader(HttpHeaders.AUTHORIZATION, bearerHeader);
		HttpResponse response = httpClient.execute(request);
		HttpEntity entity = response.getEntity();
		String content = EntityUtils.toString(entity);
		JSONObject jsonObject = new JSONObject(content);

		/**
		 * Extract list of apps with versions.
		 */
		JSONArray jsonArray = jsonObject.getJSONArray("value");

		ArrayList<String> devApps = new ArrayList<>();

		for (int i = 0; i < jsonArray.length(); i++) {
			// Store JSON objects in an array
			// Get the index of the JSON object and print the value per index
			JSONObject valueContents = (JSONObject) jsonArray.get(i);
			String displayName = (String) valueContents.get("displayName");
			devApps.add(displayName);
		}

		ns.putShared("INTUNE_INSTALLED_APPS", devApps.toString());
		logger.warn(loggerPrefix + ": Device Array: " + devApps.toString());

	}

	private String charToString(char[] temporaryPassword) {
		if (temporaryPassword == null) {
			temporaryPassword = new char[0];
		}
		char[] password = new char[temporaryPassword.length];
		System.arraycopy(temporaryPassword, 0, password, 0, temporaryPassword.length);
		return new String(password);
	}

	public static class IntuneOutcomeProvider implements org.forgerock.openam.auth.node.api.OutcomeProvider {
		@Override
		public List<Outcome> getOutcomes(PreferredLocales locales, JsonValue nodeAttributes) {
			ResourceBundle bundle = locales.getBundleInPreferredLocale(IntuneNode.BUNDLE,
					IntuneNode.class.getClassLoader());
			return ImmutableList.of(
					new Outcome(IntuneNodeOutcome.COMPLIANT.name(), bundle.getString("deviceCompliant")),
					new Outcome(IntuneNodeOutcome.NONCOMPLIANT.name(), bundle.getString("deviceNotCompliant")),
					new Outcome(IntuneNodeOutcome.INGRACEPERIOD.name(), bundle.getString("deviceNotCompliantIG")),
					new Outcome(IntuneNodeOutcome.CONFIGMANAGER.name(), bundle.getString("configManager")),
					new Outcome(IntuneNodeOutcome.CONFLICT.name(), bundle.getString("rulesConflict")),
					new Outcome(IntuneNodeOutcome.NOID.name(), bundle.getString("noID")),
					new Outcome(IntuneNodeOutcome.UNKNOWN.name(), bundle.getString("unknownCompliance")),
					new Outcome(IntuneNodeOutcome.ERROR.name(), bundle.getString("error"))

			);
		}
	}
}
