package com.spotify.heroic.http.status;

import java.util.Set;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.google.inject.Inject;
import com.spotify.heroic.cluster.ClusterManager;
import com.spotify.heroic.consumer.Consumer;
import com.spotify.heroic.metadata.MetadataBackend;
import com.spotify.heroic.metadata.MetadataManager;
import com.spotify.heroic.metric.MetricBackend;
import com.spotify.heroic.metric.MetricManager;
import com.spotify.heroic.utils.GroupMember;

@Path("/status")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class StatusResource {
    @Inject
    private Set<Consumer> consumers;

    @Inject
    private MetricManager metric;

    @Inject
    private MetadataManager metadata;

    @Inject
    private ClusterManager cluster;

    @GET
    public Response get() {
        final StatusResponse.Consumer consumers = buildConsumerStatus();
        final StatusResponse.Backend backends = buildBackendStatus();
        final StatusResponse.MetadataBackend metadataBackends = buildMetadataBackendStatus();

        final StatusResponse.Cluster cluster = buildClusterStatus();

        final boolean allOk = consumers.isOk() && backends.isOk() && metadataBackends.isOk() && cluster.isOk();

        final StatusResponse response = new StatusResponse(allOk, consumers, backends, metadataBackends, cluster);

        if (!response.isOk())
            return Response.status(Response.Status.SERVICE_UNAVAILABLE).entity(response).build();

        return Response.status(Response.Status.OK).entity(response).build();
    }

    private StatusResponse.Cluster buildClusterStatus() {
        final ClusterManager.Statistics s = cluster.getStatistics();

        if (s == null)
            return new StatusResponse.Cluster(true, 0, 0);

        final boolean ok = s.getOfflineNodes() == 0 || s.getOnlineNodes() > 0;
        return new StatusResponse.Cluster(ok, s.getOnlineNodes(), s.getOfflineNodes());
    }

    private StatusResponse.Backend buildBackendStatus() {
        final int available = metric.getBackends().size();

        int ready = 0;

        for (final GroupMember<MetricBackend> backend : metric.getBackends()) {
            if (backend.getMember().isReady())
                ready += 1;
        }

        return new StatusResponse.Backend(available == ready, available, ready);
    }

    private StatusResponse.Consumer buildConsumerStatus() {
        final int available = consumers.size();

        int ready = 0;
        long errors = 0;
        long consumingThreads = 0;
        long totalThreads = 0;
        boolean allOk = true;

        for (final Consumer consumer : consumers) {
            if (consumer.isReady()) {
                ready += 1;
                final Consumer.Statistics s = consumer.getStatistics();
                errors += s.getErrors();
                consumingThreads += s.getConsumingThreads();
                totalThreads += s.getTotalThreads();
                allOk = allOk && s.isOk();
            }
        }

        return new StatusResponse.Consumer((available == ready) && allOk, available, ready, errors, consumingThreads,
                totalThreads);
    }

    private StatusResponse.MetadataBackend buildMetadataBackendStatus() {
        final int available = metadata.getBackends().size();

        int ready = 0;

        for (final GroupMember<MetadataBackend> backend : metadata.getBackends()) {
            if (backend.getMember().isReady())
                ready += 1;
        }

        return new StatusResponse.MetadataBackend(available == ready, available, ready);
    }
}
