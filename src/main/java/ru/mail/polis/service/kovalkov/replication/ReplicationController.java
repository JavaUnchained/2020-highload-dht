package ru.mail.polis.service.kovalkov.replication;

import one.nio.http.HttpClient;
import one.nio.http.HttpException;
import one.nio.http.Response;
import one.nio.pool.PoolException;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.mail.polis.dao.DAO;
import ru.mail.polis.dao.kovalkov.DAOImpl;
import ru.mail.polis.dao.kovalkov.RecordState;
import ru.mail.polis.dao.kovalkov.TimestampDataWrapper;
import ru.mail.polis.service.kovalkov.sharding.Topology;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static java.util.Objects.nonNull;

public class ReplicationController {
    private static final Logger log = LoggerFactory.getLogger(ReplicationController.class);
    private static final String HEADER = "/v0/entity?id=";
    private static final String PROXY_HEADER = "X-OK-Proxy: True";
    private final DAOImpl dao;
    private final Topology<String> topology;
    private final Map<String, HttpClient> nodesClient;

    public ReplicationController(@NotNull final DAO dao, @NotNull final Topology<String> topology,
                                 @NotNull final Map<String, HttpClient> nodesClient) {
        this.dao = (DAOImpl) dao;
        this.topology = topology;
        this.nodesClient = nodesClient;
    }

    public Response replGet(@NotNull final String id, @NotNull final ReplicationFactor replFactor,
                            final boolean isForwarded)  {
        int replicas = 0;
        final String[] nodes = getNodeReplica(id, replFactor, isForwarded);
        final List<TimestampDataWrapper> values = new ArrayList<>();
        for (final String node: nodes) {
            try {
                Response response;
                if (topology.isMe(node)) {
                    response = internal(id);
                } else {
                    response = nodesClient.get(node).get(HEADER + id, PROXY_HEADER);
                }
                if(response.getStatus() == 500){
                    continue;
                } else if ( response.getStatus() == 404 && response.getBody().length == 0) {
                    values.add(TimestampDataWrapper.getEmptyOne());
                } else {
                    values.add(TimestampDataWrapper.wrapFromBytesAndGetOne(response.getBody()));
                }
                replicas++;
            } catch (InterruptedException | HttpException | PoolException | IOException e) {
                log.error("Exception has been occurred in replGet: ", e);
            }
        }
        if (isForwarded || replicas >= replFactor.getAck()) {
            try {
                return choseRelevant(values, nodes, isForwarded);
            } catch (IOException e) {
                return new Response(Response.GATEWAY_TIMEOUT, Response.EMPTY);
            }
        } else {
            log.error("Gateway timeout error in replGet");
            return new Response(Response.GATEWAY_TIMEOUT, Response.EMPTY);
        }
    }

    @NotNull
    private Response internal(@NotNull final String id) {
        try {
            final TimestampDataWrapper tdw = dao.getWithTimestamp(wrapId(id));
            return new Response(Response.OK, tdw.toBytes());
        } catch (IOException exc) {
            return new Response(Response.GATEWAY_TIMEOUT, Response.EMPTY);
        } catch (NoSuchElementException exc) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }
    }

    @NotNull
    private Response choseRelevant(@NotNull final List<TimestampDataWrapper> tdws, @NotNull final String[] nodes,
                                final boolean isForwarded) throws IOException {
        final TimestampDataWrapper relevantTs = TimestampDataWrapper.getRelevantTs(tdws);
        return nodes.length == 1 && isForwarded ? new Response(Response.OK, relevantTs.toBytes()) :
                relevantTs.getState() == RecordState.DELETED ? new Response(Response.NOT_FOUND, relevantTs.toBytes()) :
                        new Response(Response.OK, relevantTs.getBytes());
    }

    @NotNull
    private ByteBuffer wrapId(@NotNull final String id) {
        return ByteBuffer.wrap(id.getBytes(StandardCharsets.UTF_8));
    }

    public Response replPut(@NotNull final String id, @NotNull final ReplicationFactor replFactor,
                        final boolean isForwarded, @NotNull final byte[] value, final int a) {
        if (isForwarded) {
            try {
                dao.upsertWithTime(wrapId(id), ByteBuffer.wrap(value));
                return new Response(Response.CREATED, Response.EMPTY);
            } catch (IOException e) {
                log.error("IO in is forward replPut");
                return new Response(Response.GATEWAY_TIMEOUT, e.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
        final String[] nodes = topology.replicasFor(wrapId(id), replFactor.getFrom());
        int ack = 0;
        for (final String node : nodes) {
            Response response = null;
            try {
                if (topology.isMe(node)) {
                    dao.upsertWithTime(wrapId(id), ByteBuffer.wrap(value));
                    ack++;
                } else {
                    response = nodesClient.get(node).put(HEADER + id, value, PROXY_HEADER);
                }
            } catch (InterruptedException | PoolException | HttpException | IOException  e) {
                log.error("Cant proxying response to other node in replPut", e);
            }
            if (nonNull(response) && response.getStatus() == 201) {
                ack++;
            }
        }
        return ack >= a ? new Response(Response.CREATED, Response.EMPTY) :
                new Response(Response.GATEWAY_TIMEOUT, Response.EMPTY);
    }

    public Response replDelete(@NotNull final String id, @NotNull final ReplicationFactor replFactor,
                           final boolean isForwarded, final int a) {
        if (isForwarded) {
            try {
                dao.removeWithTimestamp(wrapId(id));
                return new Response(Response.ACCEPTED, Response.EMPTY);
            } catch (IOException e) {
                log.error("IO in is forward replDelete");
                return new Response(Response.GATEWAY_TIMEOUT, e.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
        final String[] nodes = topology.replicasFor(wrapId(id), replFactor.getFrom());
        int ack = 0;
        for (final String node : nodes) {
            Response response = null;
            try {
                if (topology.isMe(node)) {
                    dao.removeWithTimestamp(wrapId(id));
                    ack++;
                } else {
                    response = nodesClient.get(node).delete(HEADER + id, PROXY_HEADER);
                }
                if (ack == a) {
                    return new Response(Response.ACCEPTED, Response.EMPTY);
                }
            } catch (InterruptedException | PoolException | HttpException | IOException e) {
                log.error("Cant proxying response to other node in replDelete", e);
            }
            if (nonNull(response) && response.getStatus() == 202) {
                ack++;
            }
        }
        return new Response(Response.GATEWAY_TIMEOUT, Response.EMPTY);
    }


    private String[] getNodeReplica(@NotNull final String id, @NotNull final ReplicationFactor rf,
                                    final boolean isForwardedRequest) {
        return isForwardedRequest ? new String[]{ topology.getCurrentNode()} :
                topology.replicasFor(wrapId(id), rf.getFrom());
    }
}
