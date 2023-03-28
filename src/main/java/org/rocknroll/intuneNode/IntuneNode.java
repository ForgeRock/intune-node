/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions copyright [year] [name of copyright owner]".
 *
 * Copyright 2017-2018 ForgeRock AS.
 */


package org.rocknroll.intuneNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;
import javax.inject.Inject;
import com.google.common.collect.ImmutableList;
import com.sun.identity.sm.RequiredValueValidator;

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
import org.forgerock.openam.core.CoreWrapper;

import org.forgerock.openam.annotations.sm.Attribute;
import org.forgerock.openam.auth.node.api.Action;
import org.forgerock.openam.auth.node.api.Node;
import org.forgerock.openam.auth.node.api.NodeProcessException;
import org.forgerock.openam.auth.node.api.TreeContext;
import org.forgerock.openam.core.realms.Realm;
import org.forgerock.openam.sm.annotations.adapters.Password;
import org.forgerock.util.i18n.PreferredLocales;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.sun.identity.shared.debug.Debug;
import com.google.inject.assistedinject.Assisted;

@Node.Metadata(outcomeProvider  =  IntuneNode.IntuneOutcomeProvider.class,
               configClass      = IntuneNode.Config.class)
public class IntuneNode implements Node {

    private final Logger logger = LoggerFactory.getLogger(IntuneNode.class);
    private final Config config;
    private final Realm realm;
    private final CoreWrapper coreWrapper;
    private final static String DEBUG_FILE = "inTune";
    private static final String BUNDLE = "org/rocknroll/intuneNode/IntuneNode";
    protected Debug debug = Debug.getInstance(DEBUG_FILE);
    private static String deviceId;
    private static String access_token;
    private static String complianceResult;
    private static JSONObject deviceProperties;
    private static ArrayList<String> devApps = new ArrayList<>();
    private Set <String> appsBlackList;


    /**
     * Configuration for the node.
     */
    public interface Config {
        @Attribute(order = 100)
        default String inTuneHeader() {
            return "x-intune";
        }

        @Attribute(order = 200, validators = {RequiredValueValidator.class})
        String azureTenantId();

        @Attribute(order = 300, validators = {RequiredValueValidator.class})
        String appRegistrationClientId();

        @Attribute(order = 400, validators = {RequiredValueValidator.class})
        @Password
        char[] appRegistrationClientSecret();

        @Attribute(order = 500, validators = {RequiredValueValidator.class})
        String userName();

        @Attribute(order = 600, validators = {RequiredValueValidator.class})
        @Password
        char[] userPassword();

        @Attribute(order = 700)
        default boolean passDeviceInfo() {return true;}

        @Attribute(order = 800)
        default boolean passDeviceInfoSession() {return true;}

        @Attribute(order = 900)
        default String sessionPropertyName() {return "deviceProps";}


        @Attribute(order = 1000)
        default boolean extractApps() {return true;}

        @Attribute(order = 1100)
        Set<String> appsBlackList();

    }

    /**
     * Create the node using Guice injection. Just-in-time bindings can be used to obtain instances of other classes
     * from the plugin.
     *
     * @param config The service config.
     * @param realm  The realm the node is in.
     * @throws NodeProcessException If the configuration was not valid.
     */
    @Inject
    public IntuneNode(@Assisted Config config, CoreWrapper coreWrapper, @Assisted Realm realm) throws NodeProcessException {
        this.config = config;
        this.realm = realm;
        this.coreWrapper = coreWrapper;
    }



    @Override
    public Action process(TreeContext context) throws NodeProcessException {
        debug.warning("[" + DEBUG_FILE + "]: Intune Node Started");
        Action.ActionBuilder action;
        JsonValue newState = context.sharedState.copy();

        boolean deviceIdInHeader = context.request.headers.containsKey(config.inTuneHeader());
        try {
            if (deviceIdInHeader){
                deviceId = context.request.headers.get(config.inTuneHeader()).get(0).replaceAll("CN=","");
                debug.warning("[" + DEBUG_FILE + "]: deviceID: " + deviceId);
                roGrant();
                checkCompliance();
                debug.warning("[" + DEBUG_FILE + "]: Device Properties Length: " + deviceProperties.length());

                /**
                 * If app extraction is wanted then extract apps
                 */

                if (config.extractApps()) {
                    extractApps();
                    if (blacklistedAppsPresent(devApps)){
                        newState.add("blackListedAppPresent","yes");
                    } else {
                        newState.add("blackListedAppPresent","no");
                    };
                }
                /**
                 * If device properties are present and Share State storage has been enabled add them to Shared State
                 */
                if (deviceProperties.length() > 0 && config.passDeviceInfo()){

                    debug.warning("[" + DEBUG_FILE + "]: Parsing Device Properties - Save to Shared State" );

//                    Iterator<String> keys = deviceProperties.keys();
//
//                    while(keys.hasNext()) {
//                        String key = keys.next();
//                        String val = deviceProperties.getString(key);
//                        newState.add("INTUNE_"+key, val);
//                    }

                    newState.add("deviceProperties", deviceProperties.toString());
                }



                /**
                 * Evaluate device compliance check results to return certain outcome.
                 */
                switch (complianceResult) {
                    case "compliant" :
                        debug.warning("[" + DEBUG_FILE + "]: Device Compliance: compliant");
                        action = goTo(IntuneNodeOutcome.COMPLIANT);
                        break;
                    case "noncompliant" :
                        debug.warning("[" + DEBUG_FILE + "]: Device Compliance: non compliant");
                        action = goTo(IntuneNodeOutcome.NONCOMPLIANT);
                        break;
                    case "inGracePeriod" :
                        debug.warning("[" + DEBUG_FILE + "]: Device Compliance: inGracePeriod");
                        action = goTo(IntuneNodeOutcome.INGRACEPERIOD);
                        break;
                    case "unknown" :
                        debug.warning("[" + DEBUG_FILE + "]: Device Compliance: unknown");
                        action = goTo(IntuneNodeOutcome.UNKNOWN);
                        break;
                    case "conflict" :
                        debug.warning("[" + DEBUG_FILE + "]: Device Compliance: conflict");
                        action = goTo(IntuneNodeOutcome.CONFLICT);
                        break;
                    case "error" :
                        debug.warning("[" + DEBUG_FILE + "]: Device Compliance: Error");
                        action = goTo(IntuneNodeOutcome.ERROR);
                        break;
                    case "configManager" :
                        debug.warning("[" + DEBUG_FILE + "]: Device Compliance: configManager");
                        action = goTo(IntuneNodeOutcome.CONFIGMANAGER);
                        break;
                    default:
                        debug.warning("[" + DEBUG_FILE + "]: Device Compliance status: OTHER");
                        action = goTo(IntuneNodeOutcome.OTHER);
                        break;
                }
            } else {
                debug.warning("[" + DEBUG_FILE + "]: No device ID found: ");
                action = goTo(IntuneNodeOutcome.NOID);
            }
            if (deviceProperties.length() > 0 && config.passDeviceInfoSession()){
                action.putSessionProperty(config.sessionPropertyName(), deviceProperties.toString());
            }
            return action.replaceSharedState(newState).build();

        } catch (Exception e) {
            action = goTo(IntuneNodeOutcome.UNKNOWN);
            return action.replaceSharedState(newState).build();
        }

    }

    private Action.ActionBuilder goTo(IntuneNodeOutcome outcome) {
        return Action.goTo(outcome.name());
    }

    public enum IntuneNodeOutcome {
        NOID,
        COMPLIANT,
        NONCOMPLIANT,
        INGRACEPERIOD,
        ERROR,
        CONFLICT,
        CONFIGMANAGER,
        UNKNOWN,
        OTHER
    }


    private void roGrant(){
        HttpClient httpClient = HttpClientBuilder.create().build();
        HttpPost post = new HttpPost(
                "https://login.microsoftonline.com/"+config.azureTenantId()+"/oauth2/v2.0/token");
        try

        {

            List<NameValuePair> nameValuePairs = new ArrayList<NameValuePair>(1);
            nameValuePairs.add(new BasicNameValuePair("client_id", config.appRegistrationClientId()));
            nameValuePairs.add(new BasicNameValuePair("client_secret", charToString(config.appRegistrationClientSecret())));
            nameValuePairs.add(new BasicNameValuePair("grant_type", "password"));
            nameValuePairs.add(new BasicNameValuePair("username", config.userName()));
            nameValuePairs.add(new BasicNameValuePair("password", charToString(config.userPassword())));
            nameValuePairs.add(new BasicNameValuePair("scope", "DeviceManagementManagedDevices.Read.All"));

            post.setEntity(new UrlEncodedFormEntity(nameValuePairs));
            HttpResponse response = httpClient.execute(post);

            BufferedReader rd = new BufferedReader(new InputStreamReader(
                    response.getEntity().getContent()));
            HttpEntity entity = response.getEntity();
            String content = EntityUtils.toString(entity);


            JSONObject jsonObject = new JSONObject(content);
            access_token = jsonObject.getString("access_token");
            //debug.warning("[" + DEBUG_FILE + "]: Access_token: " + access_token);

        } catch (IOException | JSONException e)

        {
            debug.warning("[" + DEBUG_FILE + "]: Something went wrong while getting an access_token: " + e);
            e.printStackTrace();
        }
    }
    private void checkCompliance(){
        HttpClient httpClient = HttpClientBuilder.create().build();

        try {
            HttpGet request = new HttpGet("https://graph.microsoft.com/beta/deviceManagement/manageddevices/" + deviceId);
            String bearerHeader = "Bearer " + access_token;
            request.setHeader(HttpHeaders.AUTHORIZATION, bearerHeader);
            HttpResponse response = httpClient.execute(request);
            HttpEntity entity = response.getEntity();
            String content = EntityUtils.toString(entity);
            //debug.warning("[" + DEBUG_FILE + "]: Device Status: " + content);

            JSONObject jsonObject = new JSONObject(content);

            /**
             * Extract some of device properties returned by Intune.
             */

            // General
            String deviceId = jsonObject.getString("id");; // device ID in Intune
            String deviceName = jsonObject.getString("deviceName");
            String deviceType = jsonObject.getString("deviceType");
            String model = jsonObject.getString("model");
            String manufacturer = jsonObject.getString("manufacturer");
            String serialNumber = jsonObject.getString("serialNumber");

            // OS
            String operatingSystem = jsonObject.getString("operatingSystem");
            String osVersion = jsonObject.getString("osVersion");


            // Management
            String deviceManagementState = jsonObject.getString("managementState");
            String deviceRegistrationState = jsonObject.getString("deviceRegistrationState");
            String isSupervised = jsonObject.getString("isSupervised");
            String deviceEnrollmentType = jsonObject.getString("deviceEnrollmentType");
            String managedDeviceOwnerType = jsonObject.getString("managedDeviceOwnerType");

            // Compliance & security
            String intuneComplianceState = jsonObject.getString("complianceState");
            String jailBroken = jsonObject.getString("jailBroken");
            String lostModeState = jsonObject.getString("lostModeState");
            String isEncrypted = jsonObject.getString("isEncrypted");

            // User or Owner related
            String userPrincipalName = jsonObject.getString("userPrincipalName");
            String userDisplayName =jsonObject.getString("userDisplayName");


            /**
             * Build new json out of these properties
             */
            deviceProperties = new JSONObject()
                    .put("deviceId", deviceId)
                    .put("deviceName", deviceName)
                    .put("deviceType",deviceType)
                    .put("model", model)
                    .put("manufacturer", manufacturer)
                    .put("serialNumber", serialNumber)
                    .put("operatingSystem", operatingSystem)
                    .put("osVersion",osVersion)
                    .put("deviceRegistrationState", deviceRegistrationState)
                    .put("deviceManagementState", deviceManagementState)
                    .put("isSupervised", isSupervised)
                    .put("deviceEnrollmentType", deviceEnrollmentType)
                    .put("managedDeviceOwnerType", managedDeviceOwnerType)
                    .put("ComplianceState", intuneComplianceState)
                    .put("jailBroken", jailBroken)
                    .put("lostModeState", lostModeState)
                    .put("isEncrypted", isEncrypted)
                    .put("userPrincipalName", userPrincipalName)
                    .put("userDisplayName", userDisplayName);


            if (intuneComplianceState.equals("compliant")) {
                complianceResult = "compliant";
            } else if (deviceManagementState.equals("noncompliant")) {
                complianceResult = "noncompiant";
            } else if (deviceManagementState.equals("inGracePeriod")) {
                complianceResult = "inGracePeriod";
            } else if (deviceManagementState.equals("unknown")) {
                complianceResult = "unknown";
            } else if (deviceManagementState.equals("conflict")) {
                complianceResult = "conflict";
            } else if (deviceManagementState.equals("error")) {
                complianceResult = "error";
            } else if (deviceManagementState.equals("configManager")) {
                complianceResult = "configManager";
            }
        } catch (IOException | JSONException e) {
            complianceResult = "error";
            debug.warning("[" + DEBUG_FILE + "]: Something went wrong while inspecting device status endpoint: " + e);
        }
    }

    private void extractApps() {
        HttpClient httpClient = HttpClientBuilder.create().build();

        try {
            HttpGet request = new HttpGet("https://graph.microsoft.com/beta/deviceManagement/manageddevices/"+deviceId+"/detectedApps");
            String bearerHeader = "Bearer " + access_token;
            request.setHeader(HttpHeaders.AUTHORIZATION, bearerHeader);
            HttpResponse response = httpClient.execute(request);
            HttpEntity entity = response.getEntity();
            String content = EntityUtils.toString(entity);
            JSONObject jsonObject = new JSONObject(content);
            //debug.warning("[" + DEBUG_FILE + "]: Device apps content: " + content);
            /**
             * Extract list of apps with versions.
             */
            JSONArray jsonArray = jsonObject.getJSONArray("value");

            for (int i=0; i<jsonArray.length();i++){
                //Store JSON objects in an array
                //Get the index of the JSON object and print the value per index
                JSONObject valueContents = (JSONObject)jsonArray.get(i);
                String displayName = (String) valueContents.get("displayName");
                devApps.add(displayName);
            }
            debug.warning("[" + DEBUG_FILE + "]: Device Array: " + devApps);


        } catch (IOException | JSONException e) {
            debug.warning("[" + DEBUG_FILE + "]: Something went wrong while extracting apps: " + e);
        }
    }


    private boolean blacklistedAppsPresent(ArrayList jsonArray) throws JSONException {
        appsBlackList = config.appsBlackList();
        debug.warning("[" + DEBUG_FILE + "]: Blacklisted apps search started");
        debug.warning("[" + DEBUG_FILE + "]: current list: " + config.appsBlackList().toString());
        if (!Collections.disjoint(jsonArray, appsBlackList)) {
            debug.warning("[" + DEBUG_FILE + "]: Blacklisted app found");
            return true;
        }
//        for (int index = 0; index < jsonArray.length(); index++){
//                if (jsonArray.getString(index).Intersect){
//                    debug.warning("[" + DEBUG_FILE + "]: Blacklisted app found");
//                    return true;
//                }
//            }
        else {
            debug.warning("[" + DEBUG_FILE + "]: NO Blacklisted app found");
            return false;
        }
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
                    new Outcome(IntuneNodeOutcome.NOID.name(), bundle.getString("noID")),
                    new Outcome(IntuneNodeOutcome.COMPLIANT.name(), bundle.getString("deviceCompliant")),
                    new Outcome(IntuneNodeOutcome.NONCOMPLIANT.name(), bundle.getString("deviceNotCompliant")),
                    new Outcome(IntuneNodeOutcome.INGRACEPERIOD.name(), bundle.getString("deviceNotCompliantIG")),
                    new Outcome(IntuneNodeOutcome.UNKNOWN.name(), bundle.getString("unknownCompliance")),
                    new Outcome(IntuneNodeOutcome.CONFLICT.name(), bundle.getString("rulesConflict")),
                    new Outcome(IntuneNodeOutcome.CONFIGMANAGER.name(), bundle.getString("configManager")),
                    new Outcome(IntuneNodeOutcome.OTHER.name(), bundle.getString("other")),
                    new Outcome(IntuneNodeOutcome.ERROR.name(), bundle.getString("error"))

            );
        }
    }
}