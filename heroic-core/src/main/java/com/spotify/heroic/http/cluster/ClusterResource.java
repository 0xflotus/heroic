package com.spotify.heroic.http.cluster;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.google.inject.Inject;
import com.spotify.heroic.cluster.ClusterManager;
import com.spotify.heroic.cluster.model.NodeMetadata;
import com.spotify.heroic.cluster.model.NodeRegistryEntry;
import com.spotify.heroic.httpclient.model.DataResponse;
import com.spotify.heroic.utils.HttpAsyncUtils;
import com.spotify.heroic.utils.HttpAsyncUtils.Resume;

import eu.toolchain.async.AsyncFuture;

@Path("/cluster")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ClusterResource {
    @Inject
    private HttpAsyncUtils httpAsync;

    @Inject
    private ClusterManager cluster;

    /**
     * Encode/Decode functions, helpful when interacting with cassandra through cqlsh.
     */
    @GET
    @Path("/status")
    public Response decodeRowKey() {
        final List<ClusterNodeStatus> nodes = convert(cluster.getNodes());
        final ClusterStatus status = new ClusterStatus(nodes, cluster.getStatistics());
        return Response.status(Response.Status.OK).entity(status).build();
    }

    private List<ClusterNodeStatus> convert(List<NodeRegistryEntry> nodes) {
        final List<ClusterNodeStatus> result = new ArrayList<>();

        for (final NodeRegistryEntry e : nodes)
            result.add(convert(e));

        return result;
    }

    private ClusterNodeStatus convert(NodeRegistryEntry e) {
        final NodeMetadata m = e.getMetadata();

        return new ClusterNodeStatus(e.getClusterNode().toString(), m.getId(), m.getVersion(), m.getTags(),
                m.getCapabilities());
    }

    private static final Resume<Void, DataResponse<Boolean>> ADD_NODE = new Resume<Void, DataResponse<Boolean>>() {
        @Override
        public DataResponse<Boolean> resume(Void value) throws Exception {
            return new DataResponse<>(true);
        }
    };

    @POST
    @Path("/nodes")
    public void addNode(@Suspended AsyncResponse response, URI uri) {
        AsyncFuture<Void> callback = cluster.addStaticNode(uri);
        httpAsync.handleAsyncResume(response, callback, ADD_NODE);
    }
}
