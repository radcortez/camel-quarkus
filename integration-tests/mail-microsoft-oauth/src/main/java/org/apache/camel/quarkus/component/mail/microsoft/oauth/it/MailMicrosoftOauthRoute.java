/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.quarkus.component.mail.microsoft.oauth.it;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.CamelContext;
import org.apache.camel.builder.RouteBuilder;
import org.eclipse.microprofile.config.ConfigProvider;

@ApplicationScoped
public class MailMicrosoftOauthRoute extends RouteBuilder {

    public static final String TEST_SUBJECT = "CamelQuarkus" + System.currentTimeMillis();
    @Inject
    CamelContext camelContext;

    @Override
    public void configure() {

        if (isRealAccountConfigured()) {
            fromF("imaps://outlook.office365.com:993"
                    + "?authenticator=#auth"
                    + "&mail.imaps.auth.mechanisms=XOAUTH2"
                    + "&debugMode=true"
                    + "&delete=true"
                    //search pattern works on contains and not  start with
                    + "&searchTerm.subject=" + TEST_SUBJECT.substring(1))
                    .id("receiverRoute")
                    .autoStartup(false)
                    .to("mock:receivedMessages");
        }
    }

    private static boolean isRealAccountConfigured() {
        return isConfigValuePresent(MailMicrosoftOauthResource.USERNAME_PROPERTY) &&
                isConfigValuePresent(MailMicrosoftOauthResource.CLIENT_ID_PROPERTY) &&
                isConfigValuePresent(MailMicrosoftOauthResource.CLIENT_SECRET_PROPERTY) &&
                isConfigValuePresent(MailMicrosoftOauthResource.TENANT_ID_PROPERTY);
    }

    private static boolean isConfigValuePresent(String name) {
        return ConfigProvider.getConfig()
                .getOptionalValue(name, String.class)
                .isPresent();
    }

}
