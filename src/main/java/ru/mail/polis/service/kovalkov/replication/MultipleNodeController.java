package ru.mail.polis.service.kovalkov.replication;

import one.nio.http.HttpClient;
import one.nio.http.HttpException;
import one.nio.http.Response;
import one.nio.pool.PoolException;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.mail.polis.dao.DAO;
import ru.mail.polis.dao.kovalkov.TimestampDataWrapper;
import ru.mail.polis.service.kovalkov.sharding.Topology;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

public class MultipleNodeController {
    private static final int CREATED = 201;
    private static final int ACCEPTED = 202;
    private static final int INTERNAL_ERROR = 500;
    private static final int NOT_FOUND = 404;
    private static final Logger log = LoggerFactory.getLogger(MultipleNodeController.class);
    private static final String HEADER = "/v0/entity?id=";
    public static final String PROXY_HEADER = "X-OK-Proxy: True";
    private final DAO dao;
    private final Topology<String> topology;
    private final Map<String, HttpClient> nodesClient;
    private final ReplicationFactor replicationFactor;

    /**
     * constructor, use multiple node on one operation.
     *
     * @param dao dao implementation.
     * @param topology cluster topology.
     * @param nodesClient node clients.
     * @param replicationFactor replication factor contain ask and for for replication.
     */
    public MultipleNodeController(@NotNull final DAO dao, @NotNull final Topology<String> topology,
                                  @NotNull final Map<String, HttpClient> nodesClient,
                                  @NotNull final ReplicationFactor replicationFactor) {
        this.dao = dao;
        this.topology = topology;
        this.nodesClient = nodesClient;
        this.replicationFactor = replicationFactor;
    }

    /**
     * replication implementation of get, using whole cluster
     *
     * @param id for get from DB.
     * @param replFactor contains info about ack and from
     * @param isForwarded forwarded flag.
     * @return response form multiple node
     */
    public Response replGet(final String id, @NotNull final ReplicationFactor replFactor,
                            final boolean isForwarded) throws IOException {
        int replicas = 0;
        final String[] nodes = isForwarded ? new String[]{topology.getCurrentNode()}
                : topology.replicasFor(wrapWithCharset(id), replFactor.getFrom());
        final List<TimestampDataWrapper> values = new ArrayList<>();
        for (final String node : nodes) {
            try {
                Response response;
                if (topology.isMe(node)) {
                    response = internal(id);
                } else {
                    response = nodesClient.get(node).get(HEADER + id, PROXY_HEADER);
                }
                if (response.getStatus() == INTERNAL_ERROR) {
                    continue;
                } else if (response.getStatus() == NOT_FOUND && response.getBody().length == 0) {
                    values.add(TimestampDataWrapper.getMissingOne());
                } else {
                    values.add(TimestampDataWrapper.wrapFromBytesAndGetOne(response.getBody()));
                }
                replicas++;
            } catch (final InterruptedException | HttpException | PoolException e) {
                log.error("Exception has been occurred in replGet: ", e);
            }
        }
        if (isForwarded || replicas >= replFactor.getAck()) {
            return choseRelevant(values, nodes, isForwarded);
        } else {
            log.error("Gateway timeout error in replGet");
            return new Response(Response.GATEWAY_TIMEOUT, Response.EMPTY);
        }
    }

    @NotNull
    private Response internal(@NotNull final String id) {
        try {
            final TimestampDataWrapper tdw = dao.getWithTimestamp(
                    ByteBuffer.wrap(id.getBytes(StandardCharsets.UTF_8)));
            return new Response(Response.OK, tdw.toBytesFromValue());
        } catch (final IOException exc) {
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        } catch (final NoSuchElementException exc) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }
    }

    @NotNull
    private Response choseRelevant(@NotNull final List<TimestampDataWrapper> tdws, @NotNull final String[] nodes,
                                          final boolean isForwarded) throws IOException {
        final TimestampDataWrapper relevantTs = TimestampDataWrapper.getRelevantTs(tdws);
        if (relevantTs.isDelete()) {
            return new Response(Response.NOT_FOUND, relevantTs.toBytesFromValue());
        }
        return isForwarded && nodes.length == 1 ? new Response(Response.OK, relevantTs.toBytesFromValue())
                        : new Response(Response.OK, relevantTs.getTSBytes());
    }

    @NotNull
    public static ByteBuffer wrapWithCharset(@NotNull final String id) {
        return ByteBuffer.wrap(id.getBytes(Charset.defaultCharset()));
    }

    /**
     * replication implementation of put, using whole cluster
     *
     * @param id for put to DB.
     * @param isForwarded forwarded flag.
     * @param value for put to DB.
     * @param a ack param.
     * @return response form multiple node.
     */
    public Response replPut(@NotNull final String id,
                            final boolean isForwarded, @NotNull final byte[] value, final int a) {
        if (isForwarded) {
            try {
                dao.upsertWithTime(wrapWithCharset(id), ByteBuffer.wrap(value));
                return new Response(Response.CREATED, Response.EMPTY);
            } catch (IOException e) {
                log.error("IO in is forward replPut");
                return new Response(Response.INTERNAL_ERROR, e.toString().getBytes(Charset.defaultCharset()));
            }
        }
        final String[] nodes = topology.replicasFor(wrapWithCharset(id), replicationFactor.getFrom());
        int ack = 0;
        for (final String node : nodes) {
            try {
                if (topology.isMe(node)) {
                    dao.upsertWithTime(wrapWithCharset(id), ByteBuffer.wrap(value));
                    ack++;
                } else {
                    final Response response = nodesClient.get(node).put(HEADER + id, value, PROXY_HEADER);
                    if (response.getStatus() == CREATED) {
                        ack++;
                    }
                }
            } catch (final InterruptedException | PoolException | HttpException | IOException e) {
                log.error("Cant proxying response to other node in replPut", e);
            }
        }
        return ack >= a ? new Response(Response.CREATED, Response.EMPTY) :
                new Response(Response.GATEWAY_TIMEOUT, Response.EMPTY);
    }

    /**
     * replication implementation of get, using whole cluster
     *
     * @param id who must delete.
     * @param isForwarded forwarded flag.
     * @param a ack param
     * @return response form multiple node about delete status
     */
    public Response replDelete(@NotNull final String id, final boolean isForwarded, final int a) {
        if (isForwarded) {
            try {
                dao.removeWithTimestamp(wrapWithCharset(id));
                return new Response(Response.ACCEPTED, Response.EMPTY);
            } catch (final IOException e) {
                log.error("IO in is forward replDelete");
                return new Response(Response.INTERNAL_ERROR, e.toString().getBytes(Charset.defaultCharset()));
            }
        }
        final String[] nodes = topology.replicasFor(wrapWithCharset(id), replicationFactor.getFrom());
        int ack = 0;
        for (final String node : nodes) {
            try {
                if (topology.isMe(node)) {
                    dao.removeWithTimestamp(wrapWithCharset(id));
                    ack++;
                } else {
                    final Response response = nodesClient.get(node).delete(HEADER + id, PROXY_HEADER);
                    if (response.getStatus() == ACCEPTED) {
                        ack++;
                    }
                }
                if (ack == a) {
                    return new Response(Response.ACCEPTED, Response.EMPTY);
                }
            } catch (InterruptedException | PoolException | HttpException | IOException e) {
                log.error("Cant proxying  to other node in replDelete", e);
            }
        }
        return new Response(Response.GATEWAY_TIMEOUT, Response.EMPTY);
    }
}
