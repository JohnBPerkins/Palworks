package dev.palworks.gametest;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.google.gson.JsonParser;
import dev.palworks.net.packets.DexWorkSkillsPacket;
import dev.palworks.net.packets.WorkLevelsPacket;
import dev.palworks.suitability.SpeciesWorkSkills;
import dev.palworks.suitability.Suitability;
import dev.palworks.suitability.SuitabilityData;
import dev.palworks.worker.WorkLevelSync;
import io.netty.buffer.Unpooled;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Players are sent their Pokémon's work levels, for the PC and summary screens: their party's, and
 * again whenever they change. And every species' work skills, for the Pokédex's filter, once until
 * they change.
 */
public class WorkLevelSyncGameTests {
    private static final UUID BLASTOISE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID MAGIKARP = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @GameTest(template = Templates.EMPTY)
    public void workLevelsSurviveThePacket(GameTestHelper helper) {
        Map<UUID, Map<Suitability, Integer>> levels = Map.of(
                BLASTOISE, Map.of(Suitability.WATERING, 3, Suitability.TRANSPORTING, 1),
                MAGIKARP, Map.of()); // can't work
        RegistryFriendlyByteBuf buffer =
                new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        new WorkLevelsPacket(levels).encode(buffer);
        Map<UUID, Map<Suitability, Integer>> decoded =
                WorkLevelsPacket.decode(buffer).getLevels();
        helper.assertTrue(decoded.equals(levels), "decoded " + decoded + ", sent " + levels);
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY, batch = "work_level_sync")
    public void aPartysLevelsAreSentAndSentAgainWhenTheyChange(GameTestHelper helper) {
        // The stand-in data is put back within this tick, and nothing else runs in this batch.
        // In the party, not sent out: its levels are sent either way
        ServerPlayer owner = Workers.owner(helper, new BlockPos(1, 1, 1));
        Pokemon blastoise =
                PokemonProperties.Companion.parse("blastoise level=50").create();
        Cobblemon.INSTANCE.getStorage().getParty(owner).add(blastoise);
        UUID player = owner.getUUID();
        UUID pokemon = blastoise.getUuid();

        WorkLevelSync.syncParty(owner);
        Optional<Map<Suitability, Integer>> sent = WorkLevelSync.lastSent(player, pokemon);
        helper.assertTrue(
                sent.equals(Optional.of(Map.of(Suitability.WATERING, 3))),
                "its party's Blastoise is Watering 3, sent " + sent);

        SuitabilityData loaded = SuitabilityData.current();
        try {
            // A /reload that makes Water types Watering 2 better
            SuitabilityData.setCurrent(SuitabilityData.fromFiles(
                    Map.of("palworks:watering", JsonParser.parseString("{\"types\": {\"water\": 3}}")),
                    warning -> helper.fail("the stand-in data should load cleanly: " + warning)));
            WorkLevelSync.syncParty(owner);
            sent = WorkLevelSync.lastSent(player, pokemon);
            helper.assertTrue(
                    sent.equals(Optional.of(Map.of(Suitability.WATERING, 5))), "after the reload, sent " + sent);
        } finally {
            SuitabilityData.setCurrent(loaded);
            WorkLevelSync.forget(player);
        }
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY, batch = "work_level_sync")
    public void anAdminsChangeIsSentAndResettingItPutsTheDatapacksBack(GameTestHelper helper) {
        // Changes the data for every test, so it's in this batch, and undone within this tick
        ServerPlayer owner = Workers.owner(helper, new BlockPos(1, 1, 1));
        Pokemon blastoise =
                PokemonProperties.Companion.parse("blastoise level=50").create();
        Cobblemon.INSTANCE.getStorage().getParty(owner).add(blastoise);
        UUID player = owner.getUUID();
        UUID pokemon = blastoise.getUuid();
        MinecraftServer server = helper.getLevel().getServer();
        CommandSourceStack admin = server.createCommandSourceStack().withSuppressedOutput();
        try {
            WorkLevelSync.syncParty(owner);
            server.getCommands().performPrefixedCommand(admin, "palworks suitability set blastoise watering 1");
            server.getCommands().performPrefixedCommand(admin, "palworks suitability set blastoise lumbering 2");
            WorkLevelSync.syncParty(owner);
            Optional<Map<Suitability, Integer>> sent = WorkLevelSync.lastSent(player, pokemon);
            helper.assertTrue(
                    sent.equals(Optional.of(Map.of(Suitability.WATERING, 1, Suitability.LUMBERING, 2))),
                    "after the admin's changes, sent " + sent);

            server.getCommands().performPrefixedCommand(admin, "palworks suitability reset blastoise");
            WorkLevelSync.syncParty(owner);
            sent = WorkLevelSync.lastSent(player, pokemon);
            helper.assertTrue(
                    sent.equals(Optional.of(Map.of(Suitability.WATERING, 3))), "after the reset, sent " + sent);
        } finally {
            server.getCommands().performPrefixedCommand(admin, "palworks suitability reset all");
            WorkLevelSync.forget(player);
        }
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void speciesWorkSkillsSurviveThePacket(GameTestHelper helper) {
        Map<ResourceLocation, Set<Suitability>> skills = Map.of(
                species("charmander"), Set.of(Suitability.KINDLING),
                species("marowak"), Set.of(Suitability.MINING, Suitability.KINDLING, Suitability.NETHER),
                species("gyarados"), Set.of(Suitability.WATERING, Suitability.TRANSPORTING));
        RegistryFriendlyByteBuf buffer =
                new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        new DexWorkSkillsPacket(skills).encode(buffer);
        Map<ResourceLocation, Set<Suitability>> decoded =
                DexWorkSkillsPacket.decode(buffer).getSkills();
        helper.assertTrue(decoded.equals(skills), "decoded " + decoded + ", sent " + skills);
        helper.assertTrue(buffer.readableBytes() == 0, "all of it was read");
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY)
    public void aSpeciesWorkSkillsAreThoseOfAnyOfItsForms(GameTestHelper helper) {
        Map<ResourceLocation, Set<Suitability>> skills = SpeciesWorkSkills.current();
        helper.assertValueEqual(skills.get(species("charmander")), Set.of(Suitability.KINDLING), "Charmander's");
        // Ground as it is in Kanto, Fire and Ghost in Alola
        helper.assertValueEqual(
                skills.get(species("marowak")),
                Set.of(Suitability.MINING, Suitability.KINDLING, Suitability.NETHER),
                "Marowak's, Alolan form included");
        helper.assertTrue(!skills.containsKey(species("ekans")), "a Poison type has none, so it's left out");
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY, batch = "work_level_sync")
    public void theSpeciesWorkSkillsAreSentOnceUntilTheyChange(GameTestHelper helper) {
        // Changes the data for every test, so it's in this batch, and undone within this tick
        ServerPlayer owner = Workers.owner(helper, new BlockPos(1, 1, 1));
        MinecraftServer server = helper.getLevel().getServer();
        CommandSourceStack admin = server.createCommandSourceStack().withSuppressedOutput();
        try {
            helper.assertTrue(WorkLevelSync.sendDex(owner), "sent as its Pokédex first opens");
            helper.assertTrue(!WorkLevelSync.sendDex(owner), "not again while it has them");

            server.getCommands().performPrefixedCommand(admin, "palworks suitability set ekans kindling 2");
            helper.assertValueEqual(
                    SpeciesWorkSkills.current().get(species("ekans")),
                    Set.of(Suitability.KINDLING),
                    "Ekans' after the admin's change");
            helper.assertTrue(WorkLevelSync.sendDex(owner), "sent again once they've changed");
            helper.assertTrue(!WorkLevelSync.sendDex(owner), "and then not again");

            WorkLevelSync.forget(owner.getUUID());
            helper.assertTrue(WorkLevelSync.sendDex(owner), "sent afresh to a player who left and came back");
        } finally {
            server.getCommands().performPrefixedCommand(admin, "palworks suitability reset all");
            WorkLevelSync.forget(owner.getUUID());
        }
        helper.succeed();
    }

    private static ResourceLocation species(String name) {
        return ResourceLocation.fromNamespaceAndPath("cobblemon", name);
    }
}
