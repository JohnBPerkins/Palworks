package dev.palworks.gametest;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import kotlin.Unit;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** Real Cobblemon Pokémon for worker tests: owned by a (mock) player and sent out into the test. */
final class Workers {
    private Workers() {}

    /**
     * Send out an owned Pokémon at a spot in the test (relative coordinates). Its owner is a mock
     * player standing in the test area, so Cobblemon keeps it out.
     */
    static PokemonEntity sendOut(GameTestHelper helper, String properties, BlockPos rel) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        BlockPos abs = helper.absolutePos(rel);
        owner.moveTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5);

        Pokemon pokemon = PokemonProperties.Companion.parse(properties).create();
        Cobblemon.INSTANCE.getStorage().getParty(owner).add(pokemon);
        PokemonEntity entity = pokemon.sendOut(helper.getLevel(), Vec3.atBottomCenterOf(abs), null, e -> Unit.INSTANCE);
        if (entity == null) {
            helper.fail("Cobblemon didn't send out " + properties);
        }
        return entity;
    }
}
