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
package org.apache.camel.quarkus.component.aws2.lambda.it;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.ExtractableResponse;
import org.apache.camel.quarkus.test.support.aws2.Aws2TestResource;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@QuarkusTestResource(Aws2TestResource.class)
class Aws2LambdaTest {
    private static final Logger LOG = Logger.getLogger(Aws2LambdaTest.class);

    @Test
    public void performingOperationsOnLambdaFunctionShouldSucceed() {
        final String functionName = "cqFunction" + java.util.UUID.randomUUID().toString().replace("-", "");

        // The required role to create a function is not created immediately, so we need to retry
        await().pollDelay(6, TimeUnit.SECONDS) // never succeeded earlier than 6 seconds after creating the role
                .pollInterval(1, TimeUnit.SECONDS)
                .atMost(120, TimeUnit.SECONDS)
                .until(() -> {
                    ExtractableResponse<?> response = RestAssured.given()
                            .contentType("application/zip")
                            .body(createInitialLambdaFunctionZip())
                            .post("/aws2-lambda/function/create/" + functionName)
                            .then()
                            .extract();
                    switch (response.statusCode()) {
                    case 201:
                        LOG.infof("Lambda function %s created", functionName);
                        return true;
                    case 400:
                        LOG.infof("Could not create Lambda function %s yet (will retry): %d %s", functionName,
                                response.statusCode(), response.body().asString());
                        return false;
                    default:
                        throw new RuntimeException("Unexpected status from /aws2-lambda/function/create "
                                + response.statusCode() + " " + response.body().asString());
                    }
                });

        getUpdateListAndInvokeFunctionShouldSucceed(functionName);
        createGetDeleteAndListAliasShouldSucceed(functionName);

        RestAssured.given()
                .delete("/aws2-lambda/function/delete/" + functionName)
                .then()
                .statusCode(204);
    }

    public void getUpdateListAndInvokeFunctionShouldSucceed(String functionName) {
        final String name = "Joe " + java.util.UUID.randomUUID().toString().replace("-", "");

        RestAssured.given()
                .accept(ContentType.JSON)
                .get("/aws2-lambda/function/get/" + functionName)
                .then()
                .statusCode(200)
                .body(is(functionName));

        RestAssured.given()
                .contentType("application/zip")
                .body(createUpdatedLambdaFunctionZip())
                .put("/aws2-lambda/function/update/" + functionName)
                .then()
                .statusCode(200);

        RestAssured.given()
                .accept(ContentType.JSON)
                .get("/aws2-lambda/function/list")
                .then()
                .statusCode(200)
                .body("$", hasItem(functionName));

        /* Sometimes this does not succeed immediately */
        await().pollDelay(200, TimeUnit.MILLISECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .atMost(120, TimeUnit.SECONDS)
                .until(() -> {
                    ExtractableResponse<?> response = RestAssured.given()
                            .contentType(ContentType.JSON)
                            .body("{ \"firstName\": \"" + name + "\"}")
                            .post("/aws2-lambda/function/invoke/" + functionName)
                            .then()
                            .extract();
                    String format = "Execution of aws2-lambda/invoke/%s returned status %d and content %s";
                    LOG.infof(format, functionName, response.statusCode(), response.asString());
                    switch (response.statusCode()) {
                    case 200:
                        final String greetings = response.jsonPath().getString("greetings");
                        return greetings;
                    default:
                        return null;
                    }
                }, is("Hello updated " + name));
    }

    public void createGetDeleteAndListAliasShouldSucceed(String functionName) {

        String functionVersion = "$LATEST";
        String aliasName = "alias_LATEST_" + functionName;

        RestAssured.given()
                .queryParam("functionName", functionName)
                .queryParam("functionVersion", functionVersion)
                .queryParam("aliasName", aliasName)
                .post("/aws2-lambda/alias/create/")
                .then()
                .statusCode(201);

        RestAssured.given()
                .queryParam("functionName", functionName)
                .queryParam("aliasName", aliasName)
                .get("/aws2-lambda/alias/get/")
                .then()
                .statusCode(200)
                .body(is("$LATEST"));

        RestAssured.given()
                .queryParam("functionName", functionName)
                .queryParam("aliasName", aliasName)
                .delete("/aws2-lambda/alias/delete")
                .then()
                .statusCode(204);

        RestAssured.given()
                .queryParam("functionName", functionName)
                .accept(ContentType.JSON)
                .get("/aws2-lambda/alias/list")
                .then()
                .statusCode(200)
                .body("$", not(hasItem(aliasName)));
    }

    static byte[] createInitialLambdaFunctionZip() {
        return createLambdaFunctionZip(INITIAL_FUNCTION_SOURCE);
    }

    static byte[] createUpdatedLambdaFunctionZip() {
        return createLambdaFunctionZip(UPDATED_FUNCTION_SOURCE);
    }

    static byte[] createLambdaFunctionZip(String source) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(baos)) {
            out.putNextEntry(new ZipEntry("index.py"));
            out.write(source.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        } catch (IOException e) {
            throw new RuntimeException("Could not create a zip file", e);
        }
        return baos.toByteArray();
    }

    private static final String INITIAL_FUNCTION_SOURCE = "def handler(event, context):\n"
            + "    message = 'Hello {}'.format(event['firstName'])\n"
            + "    return {\n"
            + "        'greetings' : message\n"
            + "    }\n";

    private static final String UPDATED_FUNCTION_SOURCE = "def handler(event, context):\n"
            + "    message = 'Hello updated {}'.format(event['firstName'])\n"
            + "    return {\n"
            + "        'greetings' : message\n" +
            "    }\n";
}
