package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class S3MetricsConfigurationTest {

    private static final String NS = "http://s3.amazonaws.com/doc/2006-03-01/";

    private static String body(String inner) {
        return "<MetricsConfiguration xmlns=\"" + NS + "\">" + inner + "</MetricsConfiguration>";
    }

    @Test
    void parsesAnIdWithoutAFilter() {
        S3MetricsConfiguration parsed = S3MetricsConfiguration.parse(body("<Id>EntireBucket</Id>"));

        assertEquals("EntireBucket", parsed.id());
        assertEquals("<Id>EntireBucket</Id>", parsed.innerXml());
    }

    @Test
    void parsesEachSingleFilterPredicate() {
        assertEquals("<Id>a</Id><Filter><Prefix>logs/</Prefix></Filter>",
                S3MetricsConfiguration.parse(body("<Id>a</Id><Filter><Prefix>logs/</Prefix></Filter>")).innerXml());

        assertEquals("<Id>a</Id><Filter><Tag><Key>env</Key><Value>prod</Value></Tag></Filter>",
                S3MetricsConfiguration.parse(body(
                        "<Id>a</Id><Filter><Tag><Key>env</Key><Value>prod</Value></Tag></Filter>")).innerXml());

        String arn = "arn:aws:s3:eu-central-1:123456789012:accesspoint/ap";
        assertEquals("<Id>a</Id><Filter><AccessPointArn>" + arn + "</AccessPointArn></Filter>",
                S3MetricsConfiguration.parse(body(
                        "<Id>a</Id><Filter><AccessPointArn>" + arn + "</AccessPointArn></Filter>")).innerXml());
    }

    @Test
    void parsesAnAndConjunctionKeepingEveryTag() {
        // MetricsAndOperator.Tags is a flattened list named Tag, so the tags repeat with no
        // wrapping element and all of them have to survive the round trip.
        String parsed = S3MetricsConfiguration.parse(body("""
                <Id>a</Id>
                <Filter>
                    <And>
                        <Prefix>logs/</Prefix>
                        <Tag><Key>env</Key><Value>prod</Value></Tag>
                        <Tag><Key>team</Key><Value>core</Value></Tag>
                    </And>
                </Filter>
                """)).innerXml();

        assertEquals("<Id>a</Id><Filter><And><Prefix>logs/</Prefix>"
                + "<Tag><Key>env</Key><Value>prod</Value></Tag>"
                + "<Tag><Key>team</Key><Value>core</Value></Tag></And></Filter>", parsed);
    }

    @Test
    void escapesValuesOnTheWayBackOut() {
        String parsed = S3MetricsConfiguration.parse(
                body("<Id>a&amp;b</Id><Filter><Prefix>x&lt;y</Prefix></Filter>")).innerXml();

        assertEquals("<Id>a&amp;b</Id><Filter><Prefix>x&lt;y</Prefix></Filter>", parsed);
    }

    @Test
    void rejectsBodiesThatDoNotMatchTheSchema() {
        // Missing id, wrong root element, empty and non-XML bodies are all MalformedXML on AWS.
        for (String invalid : new String[]{
                body(""),
                body("<Id>   </Id>"),
                "<SomethingElse><Id>a</Id></SomethingElse>",
                "not xml at all",
                ""}) {
            AwsException e = assertThrows(AwsException.class, () -> S3MetricsConfiguration.parse(invalid),
                    () -> "expected rejection of: " + invalid);
            assertEquals("MalformedXML", e.getErrorCode());
            assertEquals(400, e.getHttpStatus());
        }
    }

    @Test
    void refusesToResolveExternalEntities() {
        // The parser must not read local files on behalf of a request body.
        String xxe = "<?xml version=\"1.0\"?>"
                + "<!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + "<MetricsConfiguration><Id>&xxe;</Id></MetricsConfiguration>";

        AwsException e = assertThrows(AwsException.class, () -> S3MetricsConfiguration.parse(xxe));
        assertEquals("MalformedXML", e.getErrorCode());
    }
}
