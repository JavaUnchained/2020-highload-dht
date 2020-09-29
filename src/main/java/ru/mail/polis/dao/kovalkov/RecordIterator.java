package ru.mail.polis.dao.kovalkov;

import org.jetbrains.annotations.NotNull;
import org.rocksdb.RocksIterator;
import ru.mail.polis.Record;

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.NoSuchElementException;

public class RecordIterator implements Iterator<Record>, AutoCloseable {
    private final RocksIterator rocksIterator;

    public RecordIterator(@NotNull final RocksIterator rocksIterator) {
        this.rocksIterator = rocksIterator;
    }

    @Override
    public boolean hasNext() {
        return rocksIterator.isValid();
    }

    @Override
    public Record next() {
        if(!hasNext()) throw new NoSuchElementException("No further");
        final Record record = Record.of(ByteBuffer.wrap(rocksIterator.key()),
                ByteBuffer.wrap(rocksIterator.value()));
        rocksIterator.next();
        return record;
    }

    @Override
    public void close() {
        rocksIterator.close();
    }
}