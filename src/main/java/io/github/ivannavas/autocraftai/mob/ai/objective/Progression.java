package io.github.ivannavas.autocraftai.mob.ai.objective;

import java.util.List;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

/**
 * Where the run has got to, and what that is worth.
 *
 * <p>The ladder only ever goes up. Completion is read from the inventory rather than remembered, so a
 * session that starts with wood already in the bag climbs straight past the rungs it has covered on the
 * first decision — including several at once, which is why advancing is a loop and not an if.
 *
 * <p>Reaching a rung pays a lump sum on top of whatever the rung was paying along the way. That lump is
 * what makes the difference between an aimless body and one with a direction: it is the only reward large
 * enough to be worth a long detour for.
 */
@Slf4j
public final class Progression {

    /** Paid once, on reaching a rung. Deliberately large: this is the point of the whole run. */
    private static final double ADVANCE_BONUS = 25.0;

    /** Key used in the observation once every rung is behind us. */
    private static final String FINISHED = "DONE";

    private final List<Phase> ladder;

    private int index;

    public Progression(List<Phase> ladder) {
        this.ladder = List.copyOf(ladder);
    }

    /** The ladder as it stands: the wood-and-stone opening. */
    public static Progression standard() {
        return new Progression(List.of(Rung.values()));
    }

    public boolean isFinished() {
        return index >= ladder.size();
    }

    /** The rung being climbed, or empty once they are all behind us. */
    public Optional<Phase> current() {
        return isFinished() ? Optional.empty() : Optional.of(ladder.get(index));
    }

    /**
     * Which rung we are on, as it appears in the observation key. The phase belongs in the state because
     * the right move depends on it: the same tree is worth chopping while gathering wood and worth walking
     * past while looking for stone.
     */
    public String stateKey() {
        return current().map(Objective::name).orElse(FINISHED);
    }

    /** What the current rung wants noticed in the landscape, if anything. */
    public Optional<TagKey<Block>> wanted() {
        return current().flatMap(Phase::wanted);
    }


    /** What the step was worth towards the current rung. */
    public double score(StepContext context) {
        return current().map(phase -> phase.score(context)).orElse(0.0);
    }

    /**
     * Climbs as far as the inventory allows and returns the bonus that earned.
     *
     * @return the lump sum for every rung reached on this step, or zero if none were
     */
    public double advanceIfComplete(StepContext context) {
        double bonus = 0.0;
        while (!isFinished() && ladder.get(index).isComplete(context)) {
            log.info("Reached {}", ladder.get(index).name());
            index++;
            bonus += ADVANCE_BONUS;
        }
        if (isFinished() && bonus > 0.0) {
            log.info("Every rung of the ladder is behind us");
        }
        return bonus;
    }
}
