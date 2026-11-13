package dev.palworks.sleep;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Which straw beds a Pokémon's body fits. */
class BedsTest {

    @Test
    void aSmallPokemonFitsAnyBed() {
        assertTrue(Beds.bodyFits(0.7, 1)); // Bulbasaur
        assertTrue(Beds.bodyFits(0.7, 2));
        assertTrue(Beds.bodyFits(0.7, 3));
    }

    @Test
    void aBigOneNeedsABigBed() {
        assertFalse(Beds.bodyFits(1.5, 1)); // Charizard
        assertTrue(Beds.bodyFits(1.5, 2));
        assertFalse(Beds.bodyFits(2.3, 2)); // Snorlax
        assertTrue(Beds.bodyFits(2.3, 3));
    }

    @Test
    void justTheBedsWidthFits() {
        assertTrue(Beds.bodyFits(1.0, 1)); // Machamp
        assertTrue(Beds.bodyFits(2.0, 2)); // Gyarados
    }

    @Test
    void theVeryWidestSleepInAHugeOne() {
        // Avalugg and Regigigas are 4 wide: a huge bed, as long as the space round it is clear
        assertTrue(Beds.bodyFits(4.0, 3));
        assertFalse(Beds.bodyFits(4.0, 2));
    }
}
