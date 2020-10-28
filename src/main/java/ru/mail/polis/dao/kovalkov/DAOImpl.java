package ru.mail.polis.dao.kovalkov;

import org.jetbrains.annotations.NotNull;
import org.rocksdb.BuiltinComparator;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.mail.polis.Record;
import ru.mail.polis.dao.DAO;
import ru.mail.polis.dao.kovalkov.utils.BufferConverter;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.NoSuchElementException;

public final class DAOImpl implements DAO {
    private static final Logger log = LoggerFactory.getLogger(DAOImpl.class);
    private final RocksDB db;

    private DAOImpl(final RocksDB db) {
        this.db = db;
    }

    @NotNull
    @Override
    public Iterator<Record> iterator(@NotNull final ByteBuffer from) {
        final RocksIterator rocksIterator = db.newIterator();
        rocksIterator.seek(BufferConverter.convertBytes(from));
        return new RecordIterator(rocksIterator);
    }

    @NotNull
    @Override
    public ByteBuffer get(@NotNull final ByteBuffer key) throws NoSuchElementException, IOException {
        try {
            final byte[] valueByteArray = db.get(BufferConverter.convertBytes(key));
            if (valueByteArray == null) {
                log.info("No such value key {}.", key);
                throw new NoSuchElementException("Not such value by this key");
            }
            return ByteBuffer.wrap(valueByteArray);
        } catch (RocksDBException e) {
            log.error("Getting error: ",e);
            throw new IOException(e);
        }
    }

    @Override
    public void upsert(@NotNull final ByteBuffer key, @NotNull final ByteBuffer value) throws IOException {
        try {
            db.put(BufferConverter.convertBytes(key), unfoldToBytes(value));
        } catch (RocksDBException e) {
            log.error("Rocks upsert error: ", e);
            throw new IOException("Rocks upsert error: ", e);
        }
    }

    @Override
    public void remove(@NotNull final ByteBuffer key) throws IOException {
        try {
            db.delete(BufferConverter.convertBytes(key));
        } catch (RocksDBException e) {
            log.error("Remove error: ", e);
            throw new IOException("Remove error: ", e);
        }
    }

    @Override
    public void compact() throws IOException {
        try {
            db.compactRange();
        } catch (RocksDBException e) {
            log.error("Compact error: ",e);
            throw new IOException("Compact error: ", e);
        }
    }

    @Override
    public void close() {
        db.close();
    }

    @NotNull
    public TimestampDataWrapper getWithTimestamp(@NotNull final ByteBuffer key) throws  IOException {
        try {
            final byte[] packedKey = BufferConverter.convertBytes(key);
            final byte[] valueByteArray = db.get(packedKey);
            return TimestampRecord.fromBytes(valueByteArray);
        } catch (RocksDBException e) {
            throw new IOException("getting error", e);
        }
    }
    public void removeWithTimestamp(@NotNull final ByteBuffer key) throws IOException {
        try {
            final byte[] packedKey = BufferConverter.convertBytes(key);
            final TimestampDataWrapper record = TimestampDataWrapper.tombstone(System.currentTimeMillis());
            final byte[] arrayValue = record.toBytes();
            db.put(packedKey, arrayValue);
        } catch (RocksDBException e) {
            throw new IOException("removing error",e);
        }
    }

    public void upsertWithTime(@NotNull final ByteBuffer key, @NotNull final ByteBuffer values) throws IOException {
        try {
            final TimestampDataWrapper record = TimestampDataWrapper.fromValue(values, System.currentTimeMillis());
            final byte[] packedKey = BufferConverter.convertBytes(key);
            final byte[] arrayValue = record.toBytes();
            db.put(packedKey, arrayValue);
        } catch (RocksDBException e) {
            throw new IOException("upserting error", e);
        }
    }

    private static byte[] unfoldToBytes(@NotNull final ByteBuffer buffer) {
        final ByteBuffer copy = buffer.duplicate();
        final byte[] array = new byte[copy.remaining()];
        copy.get(array);
        return array;
    }

    /**
     * Create DAO based on RocksDB.
     *
     * @param data - store data.
     * @return - new DAO.
     */
    public static DAO createDAO(final File data) throws IOException {
        try {
            final var options = new Options()
                    .setCreateIfMissing(true)
                    .setComparator(BuiltinComparator.BYTEWISE_COMPARATOR);
            final RocksDB db = RocksDB.open(options, data.getAbsolutePath());
            return new DAOImpl(db);
        } catch (RocksDBException e) {
            throw new IOException("RocksDB instantiation failed!", e);
        }
    }
}
