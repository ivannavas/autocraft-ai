package io.github.ivannavas.autocraftai.mob.ai.net;

import java.util.Arrays;
import java.util.Base64;
import java.util.Random;

/**
 * The small network a layer's beliefs are held in: two hidden layers and one output per move.
 *
 * <p>Written out by hand rather than taken from a library, for the same reason the rest of this mod is:
 * a Fabric jar has to carry everything it uses, and the smallest of the Java deep-learning libraries
 * brings two hundred megabytes of native code to do what fits on a page. Eighty thousand parameters
 * trained a batch at a time is arithmetic, not infrastructure.
 *
 * <h2>Sparse in, dense through</h2>
 * The input is a handful of buckets out of five hundred — see {@link Features} — so the first layer only
 * ever touches the columns that are on. That is what makes this cheap enough to run on the game thread:
 * a forward pass is about twenty thousand multiplies, and a tick is fifty milliseconds.
 *
 * <h2>One output at a time</h2>
 * Every learning step is about one move in one state, so the gradient enters at one output and nothing
 * flows back through the others. That is ordinary for this kind of learning and it is also what keeps a
 * batch honest: a move nobody made is not evidence that it was worth nothing.
 */
public final class Mlp {

    /**
     * Adam rather than plain gradient descent. The inputs are hashed buckets and some are hit a thousand
     * times more often than others; a single rate for all of them either crawls on the rare ones or
     * diverges on the common ones, and per-parameter rates are the cheapest fix there is.
     */
    private static final double RATE = 1e-3;
    private static final double BETA1 = 0.9;
    private static final double BETA2 = 0.999;
    private static final double EPSILON = 1e-8;
    /**
     * The most a single sample may pull. A death lands a penalty of twenty on a belief sitting near zero,
     * and an unclipped step on that one sample moves the shared trunk far enough to disturb every other
     * state that uses the same buckets. Clipping the error is what makes this Huber loss rather than
     * squared error, and it is the difference between learning from a bad night and being ruined by one.
     */
    private static final double CLIP = 1.0;

    private final int inputs;
    private final int hidden;
    private int outputs;

    private double[][] weights1;
    private double[] bias1;
    private double[][] weights2;
    private double[] bias2;
    private double[][] weights3;
    private double[] bias3;

    private double[][] gradient1;
    private double[] gradientBias1;
    private double[][] gradient2;
    private double[] gradientBias2;
    private double[][] gradient3;
    private double[] gradientBias3;

    private Adam moment1;
    private Adam momentBias1;
    private Adam moment2;
    private Adam momentBias2;
    private Adam moment3;
    private Adam momentBias3;

    /** How many samples have been accumulated since the last step, which the gradient is divided by. */
    private int batch;
    private long steps;

    private final double[] hidden1;
    private final double[] hidden2;
    private final double[] raw1;
    private final double[] raw2;

    public Mlp(int inputs, int hidden, int outputs) {
        this.inputs = inputs;
        this.hidden = hidden;
        this.outputs = Math.max(1, outputs);
        Random random = new Random(20260913L);
        weights1 = filled(hidden, inputs, random, inputs);
        bias1 = new double[hidden];
        weights2 = filled(hidden, hidden, random, hidden);
        bias2 = new double[hidden];
        // The last layer starts at nothing, so an untouched network believes every move is worth zero —
        // which is what a fresh table believed too.
        weights3 = new double[this.outputs][hidden];
        bias3 = new double[this.outputs];
        allocate();
        this.hidden1 = new double[hidden];
        this.hidden2 = new double[hidden];
        this.raw1 = new double[hidden];
        this.raw2 = new double[hidden];
    }

    /** He initialisation: the variance that keeps a ReLU stack from shrinking to nothing by its last layer. */
    private static double[][] filled(int rows, int columns, Random random, int fanIn) {
        double deviation = Math.sqrt(2.0 / fanIn);
        double[][] out = new double[rows][columns];
        for (double[] row : out) {
            for (int i = 0; i < columns; i++) {
                row[i] = random.nextGaussian() * deviation;
            }
        }
        return out;
    }

    private void allocate() {
        gradient1 = new double[hidden][inputs];
        gradientBias1 = new double[hidden];
        gradient2 = new double[hidden][hidden];
        gradientBias2 = new double[hidden];
        gradient3 = new double[outputs][hidden];
        gradientBias3 = new double[outputs];
        moment1 = new Adam(hidden, inputs);
        momentBias1 = new Adam(1, hidden);
        moment2 = new Adam(hidden, hidden);
        momentBias2 = new Adam(1, hidden);
        moment3 = new Adam(outputs, hidden);
        momentBias3 = new Adam(1, outputs);
    }

    public int outputs() {
        return outputs;
    }

    public long steps() {
        return steps;
    }

    /** What the network believes every move in this state is worth. */
    public double[] forward(int[] on, double scale) {
        for (int j = 0; j < hidden; j++) {
            double sum = bias1[j];
            double[] row = weights1[j];
            for (int index : on) {
                sum += row[index] * scale;
            }
            raw1[j] = sum;
            hidden1[j] = sum > 0 ? sum : 0.0;
        }
        for (int k = 0; k < hidden; k++) {
            double sum = bias2[k];
            double[] row = weights2[k];
            for (int j = 0; j < hidden; j++) {
                sum += row[j] * hidden1[j];
            }
            raw2[k] = sum;
            hidden2[k] = sum > 0 ? sum : 0.0;
        }
        double[] out = new double[outputs];
        for (int c = 0; c < outputs; c++) {
            double sum = bias3[c];
            double[] row = weights3[c];
            for (int k = 0; k < hidden; k++) {
                sum += row[k] * hidden2[k];
            }
            out[c] = sum;
        }
        return out;
    }

    /**
     * Adds one sample's gradient to the batch: this state, this move, and what it should have been worth.
     *
     * <p>Must be called straight after a {@link #forward} of the same state, whose activations it reads.
     */
    public void accumulate(int[] on, double scale, int action, double predicted, double target) {
        if (action < 0 || action >= outputs) {
            return;
        }
        double error = target - predicted;
        double gradient = -Math.max(-CLIP, Math.min(CLIP, error));

        gradientBias3[action] += gradient;
        double[] row3 = gradient3[action];
        for (int k = 0; k < hidden; k++) {
            row3[k] += gradient * hidden2[k];
        }

        double[] down2 = new double[hidden];
        double[] weights = weights3[action];
        for (int k = 0; k < hidden; k++) {
            if (raw2[k] > 0) {
                down2[k] = gradient * weights[k];
            }
        }
        for (int k = 0; k < hidden; k++) {
            if (down2[k] == 0.0) {
                continue;
            }
            gradientBias2[k] += down2[k];
            double[] row2 = gradient2[k];
            for (int j = 0; j < hidden; j++) {
                row2[j] += down2[k] * hidden1[j];
            }
        }

        double[] down1 = new double[hidden];
        for (int j = 0; j < hidden; j++) {
            if (raw1[j] <= 0) {
                continue;
            }
            double sum = 0.0;
            for (int k = 0; k < hidden; k++) {
                if (down2[k] != 0.0) {
                    sum += down2[k] * weights2[k][j];
                }
            }
            down1[j] = sum;
        }
        for (int j = 0; j < hidden; j++) {
            if (down1[j] == 0.0) {
                continue;
            }
            gradientBias1[j] += down1[j];
            double[] row1 = gradient1[j];
            for (int index : on) {
                row1[index] += down1[j] * scale;
            }
        }
        batch++;
    }

    /** Applies everything accumulated, averaged over the batch, and clears it. */
    public void step() {
        if (batch == 0) {
            return;
        }
        steps++;
        double over = 1.0 / batch;
        moment1.apply(weights1, gradient1, over, steps);
        momentBias1.apply(bias1, gradientBias1, over, steps);
        moment2.apply(weights2, gradient2, over, steps);
        momentBias2.apply(bias2, gradientBias2, over, steps);
        moment3.apply(weights3, gradient3, over, steps);
        momentBias3.apply(bias3, gradientBias3, over, steps);
        batch = 0;
    }

    /**
     * Another output, starting where the writer said or at what an average move is worth.
     *
     * <p>Zero was the obvious thing and it is wrong here for the reason it was wrong in the table: in a
     * state where every known move has turned out badly, a new output at zero is the best thing in the
     * row, and a skill written for one situation gets chosen in every other. With no prior the new
     * output copies the mean of the ones already there, which is "as good as the moves we have"; with a
     * prior it is a flat belief of that value until the first reward moves it.
     */
    public void grow(int columns, double prior) {
        if (columns <= outputs) {
            return;
        }
        double[][] widened = new double[columns][hidden];
        double[] widenedBias = new double[columns];
        for (int c = 0; c < outputs; c++) {
            widened[c] = weights3[c];
            widenedBias[c] = bias3[c];
        }
        double[] mean = new double[hidden];
        double meanBias = 0.0;
        if (outputs > 0) {
            for (int c = 0; c < outputs; c++) {
                for (int k = 0; k < hidden; k++) {
                    mean[k] += weights3[c][k] / outputs;
                }
                meanBias += bias3[c] / outputs;
            }
        }
        for (int c = outputs; c < columns; c++) {
            if (Double.isNaN(prior)) {
                widened[c] = mean.clone();
                widenedBias[c] = meanBias;
            } else {
                widened[c] = new double[hidden];
                widenedBias[c] = prior;
            }
        }
        weights3 = widened;
        bias3 = widenedBias;
        outputs = columns;
        gradient3 = new double[outputs][hidden];
        gradientBias3 = new double[outputs];
        moment3 = new Adam(outputs, hidden);
        momentBias3 = new Adam(1, outputs);
        batch = 0;
    }

    /** An output taken out, closing the gap: a skill let go from the book. */
    public void dropOutput(int index) {
        if (index < 0 || index >= outputs || outputs <= 1) {
            return;
        }
        double[][] narrowed = new double[outputs - 1][];
        double[] narrowedBias = new double[outputs - 1];
        for (int c = 0, at = 0; c < outputs; c++) {
            if (c == index) {
                continue;
            }
            narrowed[at] = weights3[c];
            narrowedBias[at] = bias3[c];
            at++;
        }
        weights3 = narrowed;
        bias3 = narrowedBias;
        outputs--;
        gradient3 = new double[outputs][hidden];
        gradientBias3 = new double[outputs];
        moment3 = new Adam(outputs, hidden);
        momentBias3 = new Adam(1, outputs);
        batch = 0;
    }

    /** Sets one output's belief to a flat value everywhere: a lesson taught rather than earned. */
    public void flatten(int action, double value) {
        if (action < 0 || action >= outputs) {
            return;
        }
        Arrays.fill(weights3[action], 0.0);
        bias3[action] = value;
    }

    /** Everything forgotten, back to where a fresh network starts. */
    public void clear() {
        Random random = new Random(20260913L);
        weights1 = filled(hidden, inputs, random, inputs);
        bias1 = new double[hidden];
        weights2 = filled(hidden, hidden, random, hidden);
        bias2 = new double[hidden];
        weights3 = new double[outputs][hidden];
        bias3 = new double[outputs];
        allocate();
        steps = 0;
        batch = 0;
    }

    /** This network's parameters copied into another, for the slower copy the targets are read from. */
    public void copyInto(Mlp other) {
        if (other.hidden != hidden || other.inputs != inputs) {
            return;
        }
        if (other.outputs != outputs) {
            other.grow(outputs, Double.NaN);
            while (other.outputs > outputs) {
                other.dropOutput(other.outputs - 1);
            }
        }
        for (int j = 0; j < hidden; j++) {
            other.weights1[j] = weights1[j].clone();
            other.weights2[j] = weights2[j].clone();
        }
        other.bias1 = bias1.clone();
        other.bias2 = bias2.clone();
        for (int c = 0; c < Math.min(outputs, other.outputs); c++) {
            other.weights3[c] = weights3[c].clone();
        }
        other.bias3 = Arrays.copyOf(bias3, other.outputs);
    }

    // --- persistence ------------------------------------------------------------------------------

    /**
     * The parameters as one base64 line of floats.
     *
     * <p>The tables are written as text a person can read and edit, and that was worth having. This is
     * not: eighty thousand numbers say nothing to a reader whichever way they are spelled, and written
     * out as decimals they are a megabyte a layer every hundred decisions. Floats rather than doubles
     * halve it again and cost nothing a learned belief would notice. What a reader wants from a network
     * is the rows it produces, and the overlay still shows those.
     */
    public String encode() {
        int count = hidden * inputs + hidden + hidden * hidden + hidden + outputs * hidden + outputs;
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(count * 4);
        for (double[] row : weights1) {
            for (double value : row) {
                buffer.putFloat((float) value);
            }
        }
        for (double value : bias1) {
            buffer.putFloat((float) value);
        }
        for (double[] row : weights2) {
            for (double value : row) {
                buffer.putFloat((float) value);
            }
        }
        for (double value : bias2) {
            buffer.putFloat((float) value);
        }
        for (double[] row : weights3) {
            for (double value : row) {
                buffer.putFloat((float) value);
            }
        }
        for (double value : bias3) {
            buffer.putFloat((float) value);
        }
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    /**
     * Parameters read back.
     *
     * @return whether they fitted; a file written for another shape is refused rather than half-read
     */
    public boolean decode(String encoded, int savedOutputs) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded.trim());
        } catch (IllegalArgumentException e) {
            return false;
        }
        int count = hidden * inputs + hidden + hidden * hidden + hidden + savedOutputs * hidden + savedOutputs;
        if (bytes.length != count * 4) {
            return false;
        }
        grow(savedOutputs, Double.NaN);
        while (outputs > savedOutputs) {
            dropOutput(outputs - 1);
        }
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
        for (double[] row : weights1) {
            for (int i = 0; i < row.length; i++) {
                row[i] = buffer.getFloat();
            }
        }
        for (int i = 0; i < bias1.length; i++) {
            bias1[i] = buffer.getFloat();
        }
        for (double[] row : weights2) {
            for (int i = 0; i < row.length; i++) {
                row[i] = buffer.getFloat();
            }
        }
        for (int i = 0; i < bias2.length; i++) {
            bias2[i] = buffer.getFloat();
        }
        for (double[] row : weights3) {
            for (int i = 0; i < row.length; i++) {
                row[i] = buffer.getFloat();
            }
        }
        for (int i = 0; i < bias3.length; i++) {
            bias3[i] = buffer.getFloat();
        }
        return true;
    }

    /** Per-parameter rates and the two running averages they come from. */
    private static final class Adam {

        private final double[][] average;
        private final double[][] squared;

        private Adam(int rows, int columns) {
            average = new double[rows][columns];
            squared = new double[rows][columns];
        }

        private void apply(double[][] parameters, double[][] gradients, double over, long step) {
            for (int r = 0; r < parameters.length && r < average.length; r++) {
                apply(parameters[r], gradients[r], average[r], squared[r], over, step);
            }
        }

        private void apply(double[] parameters, double[] gradients, double over, long step) {
            apply(parameters, gradients, average[0], squared[0], over, step);
        }

        private static void apply(double[] parameters, double[] gradients,
                                  double[] average, double[] squared, double over, long step) {
            double correction1 = 1.0 - Math.pow(BETA1, step);
            double correction2 = 1.0 - Math.pow(BETA2, step);
            for (int i = 0; i < parameters.length; i++) {
                double gradient = gradients[i] * over;
                gradients[i] = 0.0;
                average[i] = BETA1 * average[i] + (1 - BETA1) * gradient;
                squared[i] = BETA2 * squared[i] + (1 - BETA2) * gradient * gradient;
                double first = average[i] / correction1;
                double second = squared[i] / correction2;
                parameters[i] -= RATE * first / (Math.sqrt(second) + EPSILON);
            }
        }
    }
}
