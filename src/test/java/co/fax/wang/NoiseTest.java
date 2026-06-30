package co.fax.wang;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoiseTest {

    private static void assertInRange(double v) {
        assertTrue(v >= 0.0 && v <= 1.0, "value out of [0,1]: " + v);
    }

    @Test
    void allTypesStayInUnitRange() {
        for (NoiseType type : NoiseType.values()) {
            for (int i = 0; i < 500; i++) {
                double x = i * 0.37, y = i * 0.13, z = i * 0.71;
                assertInRange(Noise.sample(type, x, y, z, 42L, 8.0, 8.0, 8.0));
            }
        }
    }

    @Test
    void isDeterministicForSameInputs() {
        double a = Noise.sample(NoiseType.PERLIN, 3.2, 1.1, 9.4, 7L, 10.0, 6.0, 10.0);
        double b = Noise.sample(NoiseType.PERLIN, 3.2, 1.1, 9.4, 7L, 10.0, 6.0, 10.0);
        assertEquals(a, b, 0.0);
    }

    @Test
    void differentSeedsGiveDifferentFields() {
        int differences = 0;
        for (int i = 0; i < 200; i++) {
            double x = i * 0.5;
            double s1 = Noise.sample(NoiseType.SMOOTH, x, 0, 0, 1L, 8.0, 8.0, 8.0);
            double s2 = Noise.sample(NoiseType.SMOOTH, x, 0, 0, 2L, 8.0, 8.0, 8.0);
            if (Math.abs(s1 - s2) > 1e-9) differences++;
        }
        assertTrue(differences > 150, "different seeds should mostly differ, got " + differences);
    }

    @Test
    void integerLatticePointsAreInRangeAndVary() {
        // value noise at integer points equals the lattice value; should span the range over many points.
        double min = 1, max = 0;
        for (int i = 0; i < 256; i++) {
            double v = Noise.valueNoise(i, 0, 0, 99L);
            assertInRange(v);
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        assertTrue(max - min > 0.5, "value noise should span much of [0,1], span=" + (max - min));
    }

    @Test
    void smoothNoiseIsContinuous() {
        // Tiny steps in position give tiny changes in value (no jumps).
        double prev = Noise.valueNoise(0, 0, 0, 5L);
        for (int i = 1; i < 1000; i++) {
            double v = Noise.valueNoise(i * 0.01, 0, 0, 5L);
            assertTrue(Math.abs(v - prev) < 0.2, "discontinuity at i=" + i + " (" + prev + " -> " + v + ")");
            prev = v;
        }
    }

    @Test
    void equalizedNoiseSpreadsAcrossTheWholeRange() {
        // The raw noise clusters near 0.5; sample() must spread it so every part of [0,1] is hit
        // (otherwise only the middle blocks of a gradient ever appear).
        for (NoiseType type : NoiseType.values()) {
            int[] buckets = new int[10];
            int n = 0;
            for (int ix = 0; ix < 90; ix++) {
                for (int iz = 0; iz < 90; iz++) {
                    double v = Noise.sample(type, ix, 0, iz, 12345L, 9.0, 9.0, 9.0);
                    buckets[Math.min(9, (int) (v * 10))]++;
                    n++;
                }
            }
            for (int b = 0; b < 10; b++) {
                double frac = buckets[b] / (double) n;
                // Floor (strict): every block must appear — no empty band of the gradient.
                assertTrue(frac > 0.03, type + " bucket " + b + " too sparse (" + frac + ") — values clustering");
                // Ceiling (loose): allow turbulence's natural clumpiness, just no runaway single block.
                assertTrue(frac < 0.40, type + " bucket " + b + " too dense (" + frac + ")");
            }
        }
    }

    @Test
    void largerScaleMakesSlowerVariation() {
        // Over the same span, a larger feature scale should change less between adjacent samples.
        double smallScaleDelta = 0, largeScaleDelta = 0;
        double prevS = Noise.sample(NoiseType.SMOOTH, 0, 0, 0, 3L, 2.0, 2.0, 2.0);
        double prevL = Noise.sample(NoiseType.SMOOTH, 0, 0, 0, 3L, 32.0, 32.0, 32.0);
        for (int i = 1; i < 200; i++) {
            double s = Noise.sample(NoiseType.SMOOTH, i, 0, 0, 3L, 2.0, 2.0, 2.0);
            double l = Noise.sample(NoiseType.SMOOTH, i, 0, 0, 3L, 32.0, 32.0, 32.0);
            smallScaleDelta += Math.abs(s - prevS);
            largeScaleDelta += Math.abs(l - prevL);
            prevS = s; prevL = l;
        }
        assertTrue(largeScaleDelta < smallScaleDelta, "larger scale should vary more slowly");
    }
}
