package io.github.hectorvent.floci.services.docdb;

import io.github.hectorvent.floci.services.rds.RdsService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.URLENC;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Inject
    DocDbService docDbService;

    @Inject
    RdsService rdsService;

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

    @Test
    void aPersistedCollisionAnswersFromOneStoreForEveryAction() {
        // State written before creates shared one identifier space can still hold the name twice.
        // Both records are seeded through the services, as a restart would load them, since the
        // endpoint now refuses to create the second one.
        String id = "persisted-collision";
        String arn = "arn:aws:rds:us-east-1:000000000000:cluster:" + id;
        rdsService.createDbCluster(id, "aurora-postgresql", null, "admin", "secret99password",
                null, false, null);
        docDbService.createDbCluster(id, "5.0.0", "docdbadmin", "secret99password", false);

        // Describe already answers from DocumentDB for such a record, and has since long before
        // tags existed. Tagging has to agree with it: one identifier, one answer.
        query("DescribeDBClusters")
                .formParam("DBClusterIdentifier", id)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("docdb"));

        query("AddTagsToResource")
                .formParam("ResourceName", arn)
                .formParam("Tags.Tag.1.Key", "answered-by")
                .formParam("Tags.Tag.1.Value", "docdb")
        .when().post("/")
        .then().statusCode(200);

        query("ListTagsForResource")
                .formParam("ResourceName", arn)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Value>docdb</Value>"));

        // The write went to the record that answers for the identifier, not to the other one.
        assertEquals(java.util.Map.of("answered-by", "docdb"),
                docDbService.listTagsForResource(arn));
        assertTrue(rdsService.listTagsForResource(arn, "us-east-1").isEmpty());

        docDbService.deleteDbCluster(id);
        rdsService.deleteDbCluster(id, "us-east-1");
    }
}
