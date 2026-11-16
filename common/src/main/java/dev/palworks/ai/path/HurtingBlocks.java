package dev.palworks.ai.path;

import com.bedrockk.molang.runtime.value.MoValue;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.palworks.worker.WorkerState;
import dev.palworks.worker.WorkerStateManager;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

/**
 * The blocks that hurt a mob that touches them (a cactus, a sweet berry bush, fire, a lit campfire,
 * magma underfoot), and keeping Pokémon's walks out of them. Which blocks those are is Minecraft's
 * own pathfinding's say, so a mod's blocks count where the mod says so.
 *
 * <p>A worker's walk to its job is planned by {@link WorkerPathfinder}, which keeps its whole body
 * out of them all the way ({@link BodyClearance#hurtsIn}). Every other walk is Cobblemon's own
 * (following its owner, wandering in its pasture, a swimmer's or flier's way to its job), and its
 * pathfinder only minds one of these blocks underfoot: it plans straight through a berry bush, and
 * past a cactus close enough to touch. {@link #clearanceFor}, {@link #isWithin} and
 * {@link #whereOwnedPokemonWouldBe} are that pathfinder's correction, for owned Pokémon.
 */
public final class HurtingBlocks {
    private HurtingBlocks() {}

    /** Shaved off a body so one flush against a block isn't in it (as {@link BodyClearance}'s). */
    private static final double EPS = 1.0e-3;

    /** Cobblemon's setting on a Pokémon that sweet berry bushes don't hurt (one that picks them). */
    private static final String IMMUNE_TO_BERRY_BUSHES = "immune_to_sweet_berry_bush_block";

    /**
     * Whether a block Minecraft's pathfinding calls this hurts the mob when it's in it (or, for the
     * block it's standing on, on it): damage, or fire unless fire doesn't hurt this mob.
     */
    static boolean hurts(PathType type, Mob mob) {
        return type == PathType.DAMAGE_OTHER || (type == PathType.DAMAGE_FIRE && !mob.fireImmune());
    }

    /** {@link #clearanceFor}'s answer for a mob whose walks aren't kept out of anything. */
    public static final double NONE = -1.0;

    /**
     * How far round its body a walk Cobblemon is about to plan for this mob is to keep clear of the
     * blocks that hurt it; {@link #NONE} if it isn't to be kept out of them at all (it's not an
     * owned Pokémon: wild ones are left as Cobblemon has them).
     *
     * <p>A mob following a path isn't just where the path says. It counts a node as reached once
     * it's within some way of it ({@link BodyClearance#reachedWithin}), so it cuts corners by that
     * much, and stops that far short of the end: a Machamp, 1.2 blocks wide, parks up to 0.6 off
     * the spot its path ended on, and if that puts a hair of it in a cactus it's hurt there until
     * it faints. So its own walks keep that much clear, which keeps a body up to a block wide from
     * ever standing next to one of these blocks.
     *
     * <p>Only its body itself is kept out (0) on its way to a job: where it works from is somewhere
     * its body fits ({@code WorkPositions}), which may be right beside one.
     */
    public static double clearanceFor(Mob mob) {
        if (!(mob instanceof PokemonEntity pokemon) || pokemon.getOwnerUUID() == null) {
            return NONE;
        }
        WorkerState state = WorkerStateManager.get(pokemon.getPokemon());
        return state != null && state.getCurrentReservation() != null ? 0.0 : BodyClearance.reachedWithin(mob);
    }

    /**
     * Whether the mob is within {@code clearance} of a block that hurts it where it stands now (a
     * worker that has just picked a berry bush, left standing among the rest): a walk that had to
     * keep that clear all the way couldn't start.
     */
    public static boolean isWithin(PathNavigationRegion level, Mob mob, double clearance) {
        if (!(mob instanceof PokemonEntity pokemon)) {
            return false;
        }
        AABB body = mob.getBoundingBox();
        return worstIn(
                        new PathfindingContext(level, mob),
                        pokemon,
                        Mth.floor(body.minX - clearance),
                        Mth.floor(body.maxX + clearance),
                        Mth.floor(body.minY + EPS),
                        Mth.floor(body.maxY - EPS),
                        Mth.floor(body.minZ - clearance),
                        Mth.floor(body.maxZ + clearance))
                != null;
    }

    /**
     * For Cobblemon's pathfinder, of a node it takes an owned Pokémon to be able to stand at: what
     * the node is instead if its body there, or the space {@code clearance} round it (see
     * {@link #clearanceFor}), would be in a block that hurts it, or on one; null if it wouldn't.
     *
     * <p>With its body in one, what it gives back is what Minecraft's own pathfinding calls that
     * block, which Pokémon never walk into. With only the space round it in one, the same if
     * {@code mayPassClose} isn't set; if it is, what Minecraft calls being next to that block, which
     * a walk goes round where it can and through where it can't, as Minecraft's own animals do.
     *
     * <p>The node is the corner block of its body, as Minecraft's path follower has it (see
     * {@link BodyClearance}), and the body is taken at its real size: Cobblemon's own look at a
     * node stops at two blocks across. A Pokémon that Cobblemon lets pick sweet berries isn't
     * hurt by the bushes, and has to stand in one to pick it, so they aren't counted for it.
     */
    @Nullable
    public static PathType whereOwnedPokemonWouldBe(
            PathfindingContext context, int x, int y, int z, Mob mob, double clearance, boolean mayPassClose) {
        if (!(mob instanceof PokemonEntity pokemon)) {
            return null;
        }
        PathType in = worstRound(context, x, y, z, pokemon, 0.0);
        if (in != null || clearance == 0.0) {
            return in;
        }
        PathType close = worstRound(context, x, y, z, pokemon, clearance);
        if (close == null || !mayPassClose) {
            return close;
        }
        return close == PathType.DAMAGE_OTHER ? PathType.DANGER_OTHER : PathType.DANGER_FIRE;
    }

    /** {@link #worstIn} the mob's body at a node and the space this far round it, from the block it stands on up. */
    @Nullable
    private static PathType worstRound(
            PathfindingContext context, int x, int y, int z, PokemonEntity pokemon, double clearance) {
        double reach = pokemon.getBbWidth() / 2.0 + clearance - EPS;
        double centreOffset = ((int) (pokemon.getBbWidth() + 1.0F)) * 0.5;
        return worstIn(
                context,
                pokemon,
                Mth.floor(x + centreOffset - reach),
                Mth.floor(x + centreOffset + reach),
                y - 1,
                Mth.floor(y + pokemon.getBbHeight() - EPS),
                Mth.floor(z + centreOffset - reach),
                Mth.floor(z + centreOffset + reach));
    }

    /** What the worst block for the Pokémon to be in is, of those in this box of blocks that hurt it; null if none does. */
    @Nullable
    private static PathType worstIn(
            PathfindingContext context,
            PokemonEntity pokemon,
            int minX,
            int maxX,
            int minY,
            int maxY,
            int minZ,
            int maxZ) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        PathType found = null;
        for (int bx = minX; bx <= maxX; bx++) {
            for (int bz = minZ; bz <= maxZ; bz++) {
                for (int by = minY; by <= maxY; by++) {
                    PathType type = context.getPathTypeFromState(bx, by, bz);
                    if (!hurts(type, pokemon)) {
                        continue;
                    }
                    if (type != PathType.DAMAGE_OTHER) {
                        found = type;
                    } else if (!picksBerryBush(context, pos.set(bx, by, bz), pokemon)) {
                        return type; // nothing is worse
                    }
                }
            }
        }
        return found;
    }

    /** Whether this is a sweet berry bush and the Pokémon one of those the bushes don't hurt. */
    private static boolean picksBerryBush(PathfindingContext context, BlockPos pos, PokemonEntity pokemon) {
        if (!context.getBlockState(pos).is(Blocks.SWEET_BERRY_BUSH)) {
            return false;
        }
        MoValue immune = pokemon.getConfig().getMap().get(IMMUNE_TO_BERRY_BUSHES);
        return immune != null && immune.asDouble() == 1.0;
    }
}
