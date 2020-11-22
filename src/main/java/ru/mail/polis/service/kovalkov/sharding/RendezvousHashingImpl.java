package ru.mail.polis.service.kovalkov.sharding;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;

import static com.google.common.hash.Hashing.murmur3_32;

public class RendezvousHashingImpl implements Topology<String> {
    private static final Logger log = LoggerFactory.getLogger(RendezvousHashingImpl.class);
    private final String[] allNodes;
    private final String currentNode;
    final int[] nodeHashes;

    /**
     * Constructor for modular topology implementation.
     *
     * @param currentNode - this node.
     * @param allNodes - sets with all nodes.
     */
    public RendezvousHashingImpl(final String currentNode, final Set<String> allNodes) {
        assert allNodes.contains(currentNode);
        this.currentNode = currentNode;
        this.allNodes = new String[allNodes.size()];
        allNodes.toArray(this.allNodes);
        Arrays.sort(this.allNodes);
        this.nodeHashes = new int[this.allNodes.length];
        for (int i = 0; i < this.allNodes.length; i++) {
            nodeHashes[i] = murmur3_32().newHasher()
                    .putString(this.allNodes[i], StandardCharsets.UTF_8).hash().hashCode();
        }
    }

    @Override
    @NotNull
    public String identifyByKey(@NotNull final byte[] key) {
        int currentHash;
        int min = Integer.MAX_VALUE;
        String owner = null;
        for (int i = 0; i < allNodes.length; i++) {
            currentHash = nodeHashes[i] + murmur3_32().newHasher().putBytes(key).hash().hashCode();
            if (currentHash < min) {
                min = currentHash;
                owner = allNodes[i];
            }
        }
        if (owner == null) {
            log.error("Hash is null");
            throw new IllegalStateException("Hash code can't be equals null");
        }
        return owner;
    }

    @NotNull
    @Override
    public String[] replicasFor(@NotNull final ByteBuffer key, final int replicas) {
        int nodeStarterIndex = (key.hashCode() & Integer.MAX_VALUE) % allNodes.length;
        final String[] rep = new String[replicas];
        for (int i = 0; i < replicas; i++) {
            rep[i] = allNodes[nodeStarterIndex];
            nodeStarterIndex = (nodeStarterIndex + 1) % allNodes.length;
        }
        return rep;
    }

    @Override
    public int nodeCount() {
        return allNodes.length;
    }

    @NotNull
    @Override
    public String[] allNodes() {
        return allNodes.clone();
    }

    @Override
    public boolean isMe(final String node) {
        return node.equals(currentNode);
    }

    @Override
    @NotNull
    public String getCurrentNode() {
        return currentNode;
    }
}
