package io.github.hectorvent.floci.services.docdb;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.URLENC;
import static org.hamcrest.Matchers.containsString;

/**
 * One identifier space covers the RDS family.
 *
 * <p>A live account refuses to create an Aurora cluster named like an existing DocumentDB one with
 * {@code DBClusterAlreadyExistsFault}, and the reverse has to be refused too: two clusters of that
 * name in different stores would share one ARN, and no tag call could say which was meant.
 */
@QuarkusTest
@TestProfile(SharedClusterIdentifierIntegrationTest.NoContainersProfile.class)
class SharedClusterIdentifierIntegrationTest {

    /** Neither engine's container is what this is about, and starting one costs the test 30s. */
    public static class NoContainersProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.rds.mock", "true",
                          "floci.services.docdb.mock", "true");
        }
    }

    private static final String ID = "shared-identifier";
    private static final String ARN = "arn:aws:rds:us-east-1:000000000000:cluster:" + ID;
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260615/us-east-1/rds/aws4_request, "
            + "SignedHeaders=content-type;host, Signature=test";

    private static io.restassured.specification.RequestSpecification query(String action) {
        return given().header("Authorization", AUTH)
                .contentType(URLENC)
                .formParam("Action", action)
                .formParam("Version", "2014-10-31");
    }

    @Test
    void docDbCannotTakeAnIdentifierRdsAlreadyHolds() {
        query("CreateDBCluster")
                .formParam("DBClusterIdentifier", ID)
                .formParam("Engine", "aurora-postgresql")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "secret99password")
        .when().post("/")
        .then().statusCode(200);

        query("CreateDBCluster")
                .formParam("DBClusterIdentifier", ID)
                .formParam("Engine", "docdb")
                .formParam("MasterUsername", "docdbadmin")
                .formParam("MasterUserPassword", "secret99password")
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("AlreadyExists"));

        // The RDS cluster owns the ARN, and tagging reaches it rather than a DocumentDB record.
        query("AddTagsToResource")
                .formParam("ResourceName", ARN)
                .formParam("Tags.Tag.1.Key", "owner")
                .formParam("Tags.Tag.1.Value", "rds")
        .when().post("/")
        .then().statusCode(200);

        query("ListTagsForResource")
                .formParam("ResourceName", ARN)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Value>rds</Value>"));

        query("DeleteDBCluster")
                .formParam("DBClusterIdentifier", ID)
                .formParam("SkipFinalSnapshot", "true")
        .when().post("/").then().statusCode(200);
    }
}
