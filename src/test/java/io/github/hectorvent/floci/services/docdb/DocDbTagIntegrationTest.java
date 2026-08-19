package io.github.hectorvent.floci.services.docdb;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.URLENC;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Tags on DocumentDB clusters and instances.
 *
 * <p>The tag actions carry no engine or identifier, only the ARN of the resource they address, so
 * on the {@code rds} credential scope DocumentDB signs with they reach the RDS handler unless the
 * router reads that ARN. Behaviour follows a live account: tags given at create are readable back,
 * an existing key is overwritten, removing a key that is not there is not an error, and a key
 * given without a value reads back as an empty value.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DocDbTagIntegrationTest {

    private static final String CLUSTER = "tag-test-cluster";
    private static final String INSTANCE = "tag-test-instance";
    private static final String CLUSTER_ARN =
            "arn:aws:rds:us-east-1:000000000000:cluster:" + CLUSTER;
    private static final String INSTANCE_ARN =
            "arn:aws:rds:us-east-1:000000000000:db:" + INSTANCE;

    /** What the DocumentDB SDK signs with — the path that used to reach RDS and 404. */
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
    @Order(1)
    void tagsGivenAtCreateAreReadableBack() {
        query("CreateDBCluster")
                .formParam("DBClusterIdentifier", CLUSTER)
                .formParam("Engine", "docdb")
                .formParam("MasterUsername", "docdbadmin")
                .formParam("MasterUserPassword", "secret99password")
                .formParam("Tags.Tag.1.Key", "env")
                .formParam("Tags.Tag.1.Value", "demo")
                .formParam("Tags.Tag.2.Key", "owner")
                .formParam("Tags.Tag.2.Value", "me")
        .when().post("/")
        .then().statusCode(200);

        // Order is arbitrary on a live account, so only membership is asserted.
        query("ListTagsForResource")
                .formParam("ResourceName", CLUSTER_ARN)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Key>env</Key>"))
            .body(containsString("<Value>demo</Value>"))
            .body(containsString("<Key>owner</Key>"))
            .body(containsString("<Value>me</Value>"));
    }

    @Test
    @Order(2)
    void addOverwritesAnExistingKeyAndAddsNewOnes() {
        query("AddTagsToResource")
                .formParam("ResourceName", CLUSTER_ARN)
                .formParam("Tags.Tag.1.Key", "env")
                .formParam("Tags.Tag.1.Value", "changed")
                .formParam("Tags.Tag.2.Key", "zzz")
                .formParam("Tags.Tag.2.Value", "last")
        .when().post("/")
        .then().statusCode(200);

        query("ListTagsForResource")
                .formParam("ResourceName", CLUSTER_ARN)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Value>changed</Value>"))
            .body(not(containsString("<Value>demo</Value>")))
            .body(containsString("<Key>zzz</Key>"));
    }

    @Test
    @Order(3)
    void removingAKeyThatIsNotThereIsNotAnError() {
        query("RemoveTagsFromResource")
                .formParam("ResourceName", CLUSTER_ARN)
                .formParam("TagKeys.member.1", "owner")
                .formParam("TagKeys.member.2", "absent-key")
        .when().post("/")
        .then().statusCode(200);

        query("ListTagsForResource")
                .formParam("ResourceName", CLUSTER_ARN)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("<Key>owner</Key>")))
            .body(containsString("<Key>env</Key>"));
    }

    @Test
    @Order(5)
    void anInstanceCarriesItsOwnTags() {
        query("CreateDBInstance")
                .formParam("DBInstanceIdentifier", INSTANCE)
                .formParam("DBClusterIdentifier", CLUSTER)
                .formParam("Engine", "docdb")
        .when().post("/")
        .then().statusCode(200);

        query("AddTagsToResource")
                .formParam("ResourceName", INSTANCE_ARN)
                .formParam("Tags.Tag.1.Key", "role")
                .formParam("Tags.Tag.1.Value", "primary")
        .when().post("/")
        .then().statusCode(200);

        query("ListTagsForResource")
                .formParam("ResourceName", INSTANCE_ARN)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Key>role</Key>"))
            // The cluster's tags are not the instance's.
            .body(not(containsString("<Key>env</Key>")));
    }

    @Test
    @Order(6)
    void anArnInAnotherRegionOrAccountIsRejected() {
        // Storage is keyed by identifier alone, so without this check an ARN naming another
        // region would be answered with this caller's cluster. A live account gives one message
        // for both cases.
        for (String foreign : new String[]{
                "arn:aws:rds:eu-west-1:000000000000:cluster:" + CLUSTER,
                "arn:aws:rds:us-east-1:111122223333:cluster:" + CLUSTER}) {
            query("ListTagsForResource")
                    .formParam("ResourceName", foreign)
            .when().post("/")
            .then()
                .statusCode(400)
                .body(containsString("does not match an RDS resource in this region"));
        }
    }

    @Test
    @Order(6)
    void anArnForAClusterThatDoesNotExistIsNotFound() {
        query("ListTagsForResource")
                .formParam("ResourceName", "arn:aws:rds:us-east-1:000000000000:cluster:no-such-cluster")
        .when().post("/")
        .then()
            // Not a DocumentDB record, so this one stays with RDS, which owns the ARN space.
            .statusCode(404)
            .body(containsString("DBClusterNotFoundFault"));
    }

    @Test
    @Order(7)
    void tagsSurviveAModifyOfTheCluster() {
        query("ModifyDBCluster")
                .formParam("DBClusterIdentifier", CLUSTER)
                .formParam("EngineVersion", "5.0.0")
        .when().post("/")
        .then().statusCode(200);

        query("ListTagsForResource")
                .formParam("ResourceName", CLUSTER_ARN)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Key>env</Key>"));
    }

    @Test
    @Order(8)
    void aDeletedClusterHasNoTagsToRead() {
        query("DeleteDBInstance")
                .formParam("DBInstanceIdentifier", INSTANCE)
        .when().post("/").then().statusCode(200);

        query("DeleteDBCluster")
                .formParam("DBClusterIdentifier", CLUSTER)
                .formParam("SkipFinalSnapshot", "true")
        .when().post("/").then().statusCode(200);

        query("ListTagsForResource")
                .formParam("ResourceName", CLUSTER_ARN)
        .when().post("/")
        .then()
            .statusCode(404)
            .body(containsString("DBClusterNotFoundFault"));

        // And tagging it does not bring it back.
        query("AddTagsToResource")
                .formParam("ResourceName", CLUSTER_ARN)
                .formParam("Tags.Tag.1.Key", "revived")
                .formParam("Tags.Tag.1.Value", "no")
        .when().post("/")
        .then().statusCode(404);

        query("DescribeDBClusters")
                .formParam("DBClusterIdentifier", CLUSTER)
        .when().post("/")
        .then().statusCode(404);
    }
}
