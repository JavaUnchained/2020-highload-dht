package ru.mail.polis.dao;

import org.jetbrains.annotations.NotNull;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.mail.polis.Record;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.NoSuchElementException;

public class RocksDBImpl implements DAO{
    private static final Logger log = LoggerFactory.getLogger(RocksDBImpl.class);
    private RocksDB db;

    static {
        RocksDB.loadLibrary();
    }

    public RocksDBImpl(final File data) {
        final Options options = new Options().setCreateIfMissing(true);
        try{
            db = RocksDB.open(options, data.getAbsolutePath());
        } catch (RocksDBException e) {
            log.error("Rocks open error: ", e);
            throw new RuntimeException("Rocks open error: ", e);
        }
    }

    @NotNull
    @Override
    public Iterator<Record> iterator(@NotNull ByteBuffer from) throws IOException {
        final RocksIterator rocksIterator = db.newIterator();
        rocksIterator.seek(unfoldToBytes(from));
        return new RecordIterator(rocksIterator);
    }

    @NotNull
    @Override
    public ByteBuffer get(@NotNull ByteBuffer key) throws IOException{
        try {
            final byte[] value = db.get(unfoldToBytes(key));
            if (value == null) {
                log.error("Get method can't find value by key {} ",unfoldToBytes(key));
                throw new NoSuchElementException();
            }
            return ByteBuffer.wrap(value);
        } catch (RocksDBException e) {
            log.error("Rocks get error: ", e);
            throw new RuntimeException("Rocks open error: ", e);
        }
    }

    @Override
    public void upsert(@NotNull ByteBuffer key, @NotNull ByteBuffer value) {
        try {
            db.put(unfoldToBytes(key), unfoldToBytes(value));
        } catch (RocksDBException e) {
            log.error("Rocks upsert error: ", e);
            throw new RuntimeException("Rocks upsert error: ", e);
        }
    }

    @Override
    public void remove(@NotNull ByteBuffer key) {
        try {
            db.delete(unfoldToBytes(key));
        } catch (RocksDBException e) {
            log.error("Remove error: ",e);
            throw new RuntimeException("Remove error: ", e);
        }
    }

    @Override
    public void compact() {
        try {
            db.compactRange();
        } catch (RocksDBException e) {
            log.error("Compact error: ",e);
            throw new RuntimeException("Compact error: ", e);
        }
    }

    @Override
    public void close() {

        try {
            db.syncWal();
            db.closeE();
        } catch (RocksDBException e) {
            log.error("Close error: ",e);
            throw new RuntimeException("Close error: ", e);
        }
    }

    public static byte[] unfoldToBytes(@NotNull final ByteBuffer b) {
        final byte[] bytes = new byte[b.limit()]; //todo remaining ??
        b.get(bytes).clear();
        return bytes;
    }
}