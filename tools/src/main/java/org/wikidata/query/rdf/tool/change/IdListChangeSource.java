package org.wikidata.query.rdf.tool.change;

import static java.lang.Math.min;

import java.time.Instant;

import org.wikidata.query.rdf.tool.exception.RetryableException;

import com.google.common.collect.ImmutableList;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Creates a change source out of the list of IDs.
 */
public class IdListChangeSource implements Change.Source<IdListChangeSource.Batch> {
    /**
     * Build and IdChangeSource for items as opposed to properties.
     */
    public static IdListChangeSource forItems(String[] ids, int batchSize) {
        return new IdListChangeSource(ids, batchSize);
    }

    /**
     * Last id to return.
     */
    private final int batchSize;

    /**
     * List of changed entity IDs.
     */
    private final String[] ids;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "the ids field is used in few enough places that this is not an issue")
    // FIXME - it would be better to use an ImmutableList here instead of an array, but this is minor enough that it can
    // be ignored at the moment.
    public IdListChangeSource(String[] ids, int batchSize) {
        this.batchSize = batchSize;
        this.ids = ids;
    }

    @Override
    public Batch firstBatch() throws RetryableException {
        return batch(0);
    }

    @Override
    public Batch nextBatch(Batch lastBatch) throws RetryableException {
        return batch(lastBatch.nextStart);
    }

    /**
     * Batch implementation for this change source.
     */
    public final class Batch extends Change.Batch.AbstractDefaultImplementation {
        /**
         * Next id to start polling.
         */
        private final int nextStart;

        private Batch(ImmutableList<Change> changes, long advanced, int nextStart) {
            super(changes, advanced, nextStart - 1);
            this.nextStart = nextStart;
        }

        @Override
        public String advancedUnits() {
            return "ids";
        }

        @Override
        public boolean last() {
            return nextStart >= ids.length;
        }

        @Override
        public Instant leftOffDate() {
            return null;
        }
    }

    /**
     * Build a batch starting at batchStart.
     */
    private Batch batch(int batchStart) {
        int batchStop = min(batchStart + batchSize, ids.length);
        ImmutableList.Builder<Change> changes = ImmutableList.builder();
        for (int id = batchStart; id < batchStop; id++) {
            changes.add(changeFromId(ids[id], id));
        }
        return new Batch(changes.build(), batchStop - batchStart, batchStop);
    }

    /**
     * Create change from string ID.
     * String can contain revision: Q1234@12345
     */
    private Change changeFromId(String idString, int offset) {
        long revision = -1;
        Instant timestamp = null;
        if (idString.contains("@")) {
            String[] parts = idString.split("@");
            idString = parts[0];
            revision = Long.parseLong(parts[1]);
            timestamp = Instant.now();
        }
        return new Change(idString, revision, timestamp, offset);
    }

    @Override
    public void close() {
        // Nothing to do
    }
}
