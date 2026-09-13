package io.github.ivannavas.autocraftai.mob.ai.net;

/**
 * Turns a state key into the sparse vector a network reads it as.
 *
 * <h2>Why a key at all, and not the world</h2>
 * The honest thing would be to hand the network the situation itself — the exact distance, the light, the
 * height, the hour, what is in the bag — rather than the six coarse words the tables were keyed by. That
 * is worth doing and it is not what this does, because every layer's key has a different shape and the
 * brain builds all of them as strings; widening the state is a change to nine call sites and a migration
 * of every saved table, and it is the next step rather than this one. What this does is take the key
 * exactly as it already is and stop treating it as an atom.
 *
 * <h2>What that is worth on its own</h2>
 * A table key is a row and nothing else: {@code OAK_LOG|BLOCK|CLOSE|MID|B} and
 * {@code BIRCH_LOG|BLOCK|CLOSE|MID|B} are two unrelated rows, and an hour spent learning the first
 * teaches the second nothing. Split into fields, they agree about four things out of five, and a network
 * reading them as fields learns what is true of "a block close by at middling health" once instead of
 * once per kind of wood. That is the whole of the generalisation the tables were missing, and the reason
 * a run at fifteen to twenty-eight decisions a minute never filled its rows in.
 *
 * <h2>Hashed, so that nothing has to be declared</h2>
 * Every field is hashed with its own position into a fixed bank of buckets. Nothing here knows what a
 * source is, or that {@code CLOSE} is nearer than {@code FAR}, or which layer's key it is looking at —
 * the same code reads a goal key, a crafting key and a water key, and a word nobody has seen before
 * lands in a bucket of its own without anyone adding it to a list. A vocabulary would have been the
 * other way to do it, and it would have meant a table of known words to maintain and a migration every
 * time the planner invented a resource.
 *
 * <p>Two banks, not one. The first is each field on its own, which is what carries the generalisation;
 * the second is each adjacent pair of fields, which is what lets the network still tell apart two
 * situations that agree field by field and differ in how the fields combine — a hostile close at low
 * health is not the sum of "hostile", "close" and "low". Without the pairs a network of this size is
 * very nearly a linear model over the fields, and a linear model cannot learn that fleeing is right in
 * exactly one of the four corners of two flags.
 *
 * <p>Collisions are real and are left alone. Two unrelated fields landing in one bucket costs a little
 * accuracy in whichever states use them; a bank of this size makes that rare enough not to trade the
 * simplicity for a dictionary, and the network can route round it with the other bucket it has.
 */
public final class Features {

    /** Buckets for the single fields. */
    private static final int SINGLE = 256;
    /** Buckets for the adjacent pairs, which is where a key's interactions land. */
    private static final int PAIRS = 256;
    /** How wide the vector a network reads is. */
    public static final int WIDTH = SINGLE + PAIRS;
    /** The most fields a key is read as. Nine, against a goal key's six and a pursuit prefix. */
    private static final int MOST_FIELDS = 12;

    private Features() {
    }

    /**
     * The buckets a key turns on, as indices into a vector of {@link #WIDTH}.
     *
     * <p>Sparse on purpose. A key turns on about a dozen of five hundred and twelve inputs, and a first
     * layer that only touches those is twenty times less work than one that multiplies through a vector
     * of zeroes — which matters, because this runs on the game thread once per decision per layer and
     * again for every sample of every replayed batch.
     */
    public static int[] of(String key) {
        String[] fields = split(key);
        int[] on = new int[fields.length + Math.max(0, fields.length - 1)];
        int at = 0;
        for (int field = 0; field < fields.length; field++) {
            on[at++] = bucket(field, fields[field], 0, SINGLE);
        }
        for (int field = 0; field + 1 < fields.length; field++) {
            on[at++] = bucket(field, fields[field] + '' + fields[field + 1], SINGLE, PAIRS);
        }
        return on;
    }

    /**
     * The value each of those inputs carries.
     *
     * <p>One over the root of how many there are, rather than one. A key with four fields and a key with
     * nine would otherwise arrive at the first layer with twice the magnitude, and the network would read
     * the length of the key as a signal about the situation — which it is not, it is a signal about which
     * layer's table asked.
     */
    public static double scale(int[] on) {
        return on.length == 0 ? 0.0 : 1.0 / Math.sqrt(on.length);
    }

    private static String[] split(String key) {
        String[] fields = key.split("\\|", MOST_FIELDS);
        return fields.length == 0 ? new String[] {""} : fields;
    }

    /**
     * A field and its position, mixed into one bucket of a bank.
     *
     * <p>The position is part of what is hashed, so that the same word in two places is two different
     * facts: {@code STONE} as the thing being looked for and {@code STONE} as the thing underfoot are
     * not the same situation, and a hash that ignored where the word sat would say they were.
     */
    private static int bucket(int position, String field, int base, int size) {
        // The finaliser from murmur3, which is what turns Java's very weak string hash into something
        // that spreads. Without it, keys that differ by one character land in neighbouring buckets and
        // the first layer sees the two as nearly the same input.
        long mixed = field.hashCode() * 0x9E3779B97F4A7C15L + position * 0xBF58476D1CE4E5B9L;
        mixed ^= mixed >>> 30;
        mixed *= 0xBF58476D1CE4E5B9L;
        mixed ^= mixed >>> 27;
        mixed *= 0x94D049BB133111EBL;
        mixed ^= mixed >>> 31;
        return base + (int) Math.floorMod(mixed, size);
    }
}
