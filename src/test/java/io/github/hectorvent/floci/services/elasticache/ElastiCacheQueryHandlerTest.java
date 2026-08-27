package io.github.hectorvent.floci.services.elasticache;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.elasticache.proxy.SigV4Validator;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import io.github.hectorvent.floci.services.elasticache.model.ReplicationGroup;
import io.github.hectorvent.floci.services.elasticache.model.ReplicationGroupSettings;
import io.github.hectorvent.floci.services.elasticache.model.ReplicationGroupStatus;
import io.github.hectorvent.floci.services.elasticache.model.AuthMode;
import io.github.hectorvent.floci.services.elasticache.model.Endpoint;
import io.github.hectorvent.floci.core.common.AwsException;
import java.util.List;
import java.util.Map;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Verifies the empty-list read responses for the subnet/parameter group describes, so
 * SDK clients get a valid 200 instead of failing with UnsupportedOperation (400).
 */
class ElastiCacheQueryHandlerTest {

    private ElastiCacheQueryHandler handler;
    private ElastiCacheService service;

    @BeforeEach
    void setUp() {
        SigV4Validator sigV4Validator = mock(SigV4Validator.class);
        service = mock(ElastiCacheService.class);
        ElastiCacheMemcachedService memcachedService = mock(ElastiCacheMemcachedService.class);
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getRegion()).thenReturn("us-east-1");
        when(regionResolver.getAccountId()).thenReturn("000000000000");
        handler = new ElastiCacheQueryHandler(sigV4Validator, service, memcachedService, regionResolver);
    }

    @Test
    void describeCacheSubnetGroups_returnsEmptyWrapperWithoutMarker() {
        Response response = handler.handle("DescribeCacheSubnetGroups", params(), "us-east-1");

        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<DescribeCacheSubnetGroupsResult><CacheSubnetGroups></CacheSubnetGroups></DescribeCacheSubnetGroupsResult>"),
                "Expected empty CacheSubnetGroups wrapper inside the Result element");
        assertFalse(body.contains("<Marker>"), "Empty list must omit Marker");
    }

    @Test
    void describeCacheParameterGroups_returnsEmptyWrapperWithoutMarker() {
        Response response = handler.handle("DescribeCacheParameterGroups", params(), "us-east-1");

        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<DescribeCacheParameterGroupsResult><CacheParameterGroups></CacheParameterGroups></DescribeCacheParameterGroupsResult>"),
                "Expected empty CacheParameterGroups wrapper inside the Result element");
        assertFalse(body.contains("<Marker>"), "Empty list must omit Marker");
    }

    @Test
    void unsupportedOperationStillReturnsQueryError() {
        Response response = handler.handle("NoSuchAction", params(), "us-east-1");

        assertEquals(400, response.getStatus());
        assertTrue(((String) response.getEntity()).contains("UnsupportedOperation"));
    }

    private static MultivaluedMap<String, String> params() {
        return new MultivaluedHashMap<>();
    }

    private static ReplicationGroup group(String id) {
        ReplicationGroup g = new ReplicationGroup(id, "d", ReplicationGroupStatus.AVAILABLE, AuthMode.NO_AUTH,
                new Endpoint("localhost", 6379), java.time.Instant.now(), 6379);
        g.setArn("arn:aws:elasticache:us-east-1:000000000000:replicationgroup:" + id);
        return g;
    }

    @Test
    void createReplicationGroup_passesSettingsAndTagsToService() {
        when(service.createReplicationGroup(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(group("g1"));
        MultivaluedMap<String, String> p = params();
        p.add("ReplicationGroupId", "g1");
        p.add("ReplicationGroupDescription", "d");
        p.add("AtRestEncryptionEnabled", "true");
        p.add("KmsKeyId", "alias/cache");
        p.add("SnapshotRetentionLimit", "7");
        p.add("SnapshotWindow", "06:30-07:30");
        p.add("Tags.Tag.1.Key", "Name");
        p.add("Tags.Tag.1.Value", "g1");

        assertEquals(200, handler.handle("CreateReplicationGroup", p, "us-east-1").getStatus());

        verify(service).createReplicationGroup(eq("g1"), eq("d"), eq(AuthMode.NO_AUTH), isNull(), eq("us-east-1"),
                eq(new ReplicationGroupSettings(true, "alias/cache", 7, "06:30-07:30")), eq(Map.of("Name", "g1")));
    }

    @Test
    void createReplicationGroup_readsTheEncryptionFlagAsAwsDoes() {
        when(service.createReplicationGroup(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(group("g1"));
        ArgumentCaptor<ReplicationGroupSettings> captor = ArgumentCaptor.forClass(ReplicationGroupSettings.class);
        // a live account reads anything but "false" as true — probed with banana, yes and "TRUE "
        for (String value : new String[] {"true", "banana", "yes", "TRUE "}) {
            MultivaluedMap<String, String> p = params();
            p.add("ReplicationGroupId", "g1");
            p.add("AtRestEncryptionEnabled", value);
            handler.handle("CreateReplicationGroup", p, "us-east-1");
        }
        for (String value : new String[] {"false", "FALSE"}) {
            MultivaluedMap<String, String> p = params();
            p.add("ReplicationGroupId", "g1");
            p.add("AtRestEncryptionEnabled", value);
            handler.handle("CreateReplicationGroup", p, "us-east-1");
        }
        MultivaluedMap<String, String> omitted = params();
        omitted.add("ReplicationGroupId", "g1");
        handler.handle("CreateReplicationGroup", omitted, "us-east-1");

        verify(service, times(7)).createReplicationGroup(any(), any(), any(), any(), any(), captor.capture(), any());
        List<Boolean> seen = captor.getAllValues().stream().map(ReplicationGroupSettings::atRestEncryptionEnabled).toList();
        assertEquals(java.util.Arrays.asList(true, true, true, true, false, false, null), seen);
    }

    @Test
    void describeReplicationGroups_emitsStoredSettingsAndArn() {
        ReplicationGroup g = group("g1");
        g.setAtRestEncryptionEnabled(true);
        g.setKmsKeyId("arn:aws:kms:us-east-1:000000000000:key/k1");
        g.setSnapshotRetentionLimit(7);
        g.setSnapshotWindow("06:30-07:30");
        when(service.listReplicationGroups("g1")).thenReturn(List.of(g));
        MultivaluedMap<String, String> p = params();
        p.add("ReplicationGroupId", "g1");

        String body = (String) handler.handle("DescribeReplicationGroups", p, "us-east-1").getEntity();

        assertTrue(body.contains("<AtRestEncryptionEnabled>true</AtRestEncryptionEnabled>"), body);
        assertTrue(body.contains("<KmsKeyId>arn:aws:kms:us-east-1:000000000000:key/k1</KmsKeyId>"), body);
        assertTrue(body.contains("<SnapshotRetentionLimit>7</SnapshotRetentionLimit>"), body);
        assertTrue(body.contains("<SnapshotWindow>06:30-07:30</SnapshotWindow>"), body);
        assertTrue(body.contains("<ARN>arn:aws:elasticache:us-east-1:000000000000:replicationgroup:g1</ARN>"), body);
    }

    @Test
    void listTagsForResource_readsAReplicationGroupByItsArn() {
        ReplicationGroup g = group("g1");
        g.setTags(new java.util.LinkedHashMap<>(Map.of("Name", "g1")));
        when(service.getReplicationGroup("g1")).thenReturn(g);
        when(service.getReplicationGroup("absent")).thenThrow(
                new AwsException("ReplicationGroupNotFoundFault", "Replication group absent not found.", 404));

        MultivaluedMap<String, String> p = params();
        p.add("ResourceName", "arn:aws:elasticache:us-east-1:000000000000:replicationgroup:g1");
        String body = (String) handler.handle("ListTagsForResource", p, "us-east-1").getEntity();
        assertTrue(body.contains("<Key>Name</Key>"), body);

        p = params();
        p.add("ResourceName", "arn:aws:elasticache:us-east-1:000000000000:replicationgroup:absent");
        Response response = handler.handle("ListTagsForResource", p, "us-east-1");
        assertEquals(404, response.getStatus());
        assertTrue(((String) response.getEntity()).contains("ReplicationGroupNotFoundFault"));

        // the store keys groups by id alone: a same-named group created under another region is
        // not the one this ARN names
        ReplicationGroup elsewhere = group("g2");
        elsewhere.setArn("arn:aws:elasticache:eu-west-1:000000000000:replicationgroup:g2");
        elsewhere.setTags(new java.util.LinkedHashMap<>(Map.of("Name", "west")));
        when(service.getReplicationGroup("g2")).thenReturn(elsewhere);
        p = params();
        p.add("ResourceName", "arn:aws:elasticache:us-east-1:000000000000:replicationgroup:g2");
        response = handler.handle("ListTagsForResource", p, "us-east-1");
        assertEquals(404, response.getStatus(), (String) response.getEntity());
        assertFalse(((String) response.getEntity()).contains("west"));
    }

    @Test
    void modifyReplicationGroup_passesSnapshotSettingsOnly() {
        when(service.modifyReplicationGroup(eq("g1"), isNull(), isNull(), any())).thenReturn(group("g1"));
        MultivaluedMap<String, String> p = params();
        p.add("ReplicationGroupId", "g1");
        p.add("SnapshotRetentionLimit", "3");
        p.add("SnapshotWindow", "01:00-02:00");
        p.add("AtRestEncryptionEnabled", "true");
        p.add("KmsKeyId", "alias/other");

        assertEquals(200, handler.handle("ModifyReplicationGroup", p, "us-east-1").getStatus());
        verify(service).modifyReplicationGroup("g1", null, null, new ReplicationGroupSettings(null, null, 3, "01:00-02:00"));
    }
}
