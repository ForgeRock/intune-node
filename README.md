<!--
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
* Copyright 2018 ForgeRock AS.
-->
# Microsoft Intune Node


This node integrates with Microsoft Intune via Graph API. It evaluates the device's compliance posture and returns the result. It can also save device information to the SharedState that can subsequently be used by other nodes.


From Microsoft - "Important: Microsoft Graph APIs under the /beta version are subject to change; production use is not supported."


##  Configuration properties of the node


- DeviceID Attribute Name
   >This is the value of the SSL_Client_S_DN from the client certificate presented at the TLS termination gateway. The default location for this value is expected in the Request Header. Format should be CN=f47d8e59-b60e-48a5-adc1-622cb2244zzz
- DeviceID in SharedState
   >The default location for DeviceID is expected to be in the Header.  Setting this value to true indicates looking in the SharedState for the DeviceID instead of the Header. The format is the same: CN=f47d8e59-b60e-48a5-adc1-622cb2244zzz
- Directory (tenant) ID
   >Tenant Id is the Azure Active Directory\u2019s Global unique identifier (GUID)
- Application (client) ID
   >The application ID, or client ID, is a value the Microsoft identity platform assigns to your application when you register it in Azure AD.
- Client Secret
   >Sometimes called an application password, a client secret is a string value your app can use in place of a certificate to identify itself.
- Azure Admin User Name
   >This is the administrative username.
- Azure Admin User Password
   >This is the administrative password.
- Save Device Properties to Shared State
   >If enabled, all device info will be saved to the SharedState with INTUNE_ prepended to the key name. Null and empty string values will not be placed into SharedState.  Details on possible return properties can be found [here](https://learn.microsoft.com/en-us/graph/api/resources/intune-devices-manageddevice?view=graph-rest-beta#properties).
- Save installed apps to Shared State
   >If enabled, the apps installed on the Mobile Device are extracted and saved to the Shared State with the key name - INTUNE_INSTALLED_APPS


## Outcomes


The following details have been derived from the Compliance State found [here](https://learn.microsoft.com/en-us/graph/api/resources/intune-devices-compliancestate?view=graph-rest-beta).
- Compliant
   > Take if the device is compliant
- Not Compliant
   > Take if the device is not compliant
- In Grace Period
   > Take if the device is not compliant but still has access to corporate resources
- Config Manager
   > Take if Managed by Config Manager
- Conflict
   > Take if a conflict occurs with other rules
- No Id
   > Take if no DeviceId found in the Header or SharedState
- Status Unknown
   > Take if unknown
- Error
   > Take if any error occurs, the stacktrace and message are placed in the SharedState


## Prerequisite


Note: The Microsoft Graph API for Intune requires an active Intune license for the tenant.


The DeviceId needs to be obtained from the TLS handshake.  That DeviceId needs to be placed in the Header between a proxy and Identity Cloud or needs to be placed in the SharedState.  Either way needs to occur before reaching this Microsoft Intune Node. 


##  Additional Steps for Self-Managed Install
For self-managed deployments, copy the .jar file from the ../target directory into the ../web-container/webapps/openam/WEB-INF/lib directory where AM is deployed.  Restart the web container to pick up the new node.  The node will then appear in the authentication trees components palette.


## Sample Tree


![ScreenShot](./example.png)


The code in this repository has binary dependencies that live in the ForgeRock maven repository. Maven can be configured to authenticate to this repository by following the following [ForgeRock Knowledge Base Article](https://backstage.forgerock.com/knowledge/kb/article/a74096897).
      
The sample code described herein is provided on an "as is" basis, without warranty of any kind, to the fullest extent permitted by law. ForgeRock does not warrant or guarantee the individual success developers may have in implementing the sample code on their development platforms or in production configurations.


ForgeRock does not warrant, guarantee or make any representations regarding the use, results of use, accuracy, timeliness or completeness of any data or information relating to the sample code. ForgeRock disclaims all warranties, expressed or implied, and in particular, disclaims all warranties of merchantability, and warranties related to the code, or any service or software related thereto.


ForgeRock shall not be liable for any direct, indirect or consequential damages or costs of any type arising out of any action taken by you or others related to the sample code.


[forgerock_platform]: https://www.forgerock.com/platform/ 



