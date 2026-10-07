package art.arcane.optics.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.optics.internal.stream.EncodedPlate;
import art.arcane.optics.internal.stream.FrameSplitter;
import art.arcane.optics.internal.stream.PlateStreamEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntSupplier;
import java.util.zip.Deflater;

import org.junit.jupiter.api.Test;

import art.arcane.optics.scan.ProjectorSample;
import art.arcane.optics.plate.ViewPlate;
import art.arcane.optics.plate.ViewPlateBuilder;

final class DomePlateSizeTest {
    static final long WORLD_SEED = 0x5EEDD0A3L;
    static final long PLACEMENT_SEED = 42L;
    private static final int TOTAL_BUDGET_BYTES = 160 * 1024;
    private static final int PLATE_BUDGET_BYTES = 35 * 1024;

    record PlateReport(String name, long rawBytes, long deflatedBytes, int bricks, double airShare, double blockShare, double backingShare,
                       double occludedShare) {
    }

    static List<PlateReport> report(List<ViewPlate<String>> plates, List<DomePlates.Scenario> scenarios, SessionPalette palette,
                                    PlateStreamEncoder<String> encoder, FrameSplitter splitter) throws ViewStreamProtocolException {
        List<PlateReport> reports = new ArrayList<PlateReport>(plates.size());
        SessionPalette.Cursor cursor = palette.cursor();
        int[] seq = new int[1];
        IntSupplier sequence = () -> seq[0]++;
        for (int i = 0; i < plates.size(); i++) {
            ViewPlate<String> plate = plates.get(i);
            EncodedPlate encoded = encoder.encode(plate, null, false);
            List<ViewStreamMessage> group = new ArrayList<ViewStreamMessage>();
            List<ViewStreamMessage.PaletteEntry> pending = cursor.pending(encoded.referencedIds());
            if (!pending.isEmpty()) {
                group.add(new ViewStreamMessage.Palette(pending));
            }
            group.add(encoded.begin(1 + i, 1, true, 0x5A17L));
            group.add(encoded.bricksMessage(1 + i, 1));
            group.add(encoded.end(1 + i, 1));
            List<byte[]> frames = splitter.split(group, sequence);
            long raw = 0L;
            long deflated = 0L;
            for (byte[] frame : frames) {
                raw += frame.length;
                deflated += deflate(frame).length;
            }
            double cells = plate.box().cells();
            reports.add(new PlateReport(scenarios.get(i).name(), raw, deflated, encoded.brickCount(),
                (encoded.kindCount(ProjectorSample.Kind.REMOTE_AIR.ordinal()) + encoded.kindCount(ProjectorSample.Kind.values().length)) / cells,
                encoded.kindCount(ProjectorSample.Kind.BLOCK.ordinal()) / cells,
                encoded.kindCount(ProjectorSample.Kind.BACKING_BLOCK.ordinal()) / cells,
                encoded.kindCount(ProjectorSample.Kind.OCCLUDED.ordinal()) / cells));
        }
        return reports;
    }

    static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater(6);
        deflater.setInput(data);
        deflater.finish();
        byte[] buffer = new byte[data.length + 64];
        int produced = 0;
        while (!deflater.finished()) {
            produced += deflater.deflate(buffer, produced, buffer.length - produced);
        }
        deflater.end();
        byte[] out = new byte[produced];
        System.arraycopy(buffer, 0, out, 0, produced);
        return out;
    }

    @Test
    void nineDomePlatesStayUnderTheStreamBudget() throws ViewStreamProtocolException {
        SyntheticWorld world = new SyntheticWorld(WORLD_SEED);
        List<DomePlates.Scenario> scenarios = DomePlates.scenarios(world, PLACEMENT_SEED);
        List<ViewPlate<String>> plates = new ArrayList<ViewPlate<String>>(9);
        for (DomePlates.Scenario scenario : scenarios) {
            plates.add(ViewPlateBuilder.build(scenario.request(world, false)));
        }
        SessionPalette palette = new SessionPalette();
        PlateStreamEncoder<String> encoder = new PlateStreamEncoder<String>(palette, state -> state);
        FrameSplitter splitter = new FrameSplitter(ViewStreamLimits.DEFAULT_MAX_FRAME_BYTES, false, ViewStreamFixtures.CODEC);
        List<PlateReport> reports = report(plates, scenarios, palette, encoder, splitter);

        assertEquals(9, reports.size());
        long total = 0L;
        StringBuilder detail = new StringBuilder();
        for (PlateReport report : reports) {
            total += report.deflatedBytes();
            detail.append(String.format(Locale.ROOT, "%s raw=%dKiB defl=%dKiB bricks=%d air=%.2f block=%.2f backing=%.2f occluded=%.2f; ",
                report.name(), report.rawBytes() / 1024, report.deflatedBytes() / 1024, report.bricks(), report.airShare(), report.blockShare(),
                report.backingShare(), report.occludedShare()));
        }
        for (PlateReport report : reports) {
            assertTrue(report.deflatedBytes() <= PLATE_BUDGET_BYTES, report.name() + " deflates to " + report.deflatedBytes() + " bytes");
            assertTrue(report.airShare() >= 0.03D && report.airShare() <= 0.85D, report.name() + " air share " + report.airShare());
            assertTrue(report.occludedShare() >= 0.10D && report.occludedShare() <= 0.75D, report.name() + " occluded share " + report.occludedShare());
            assertTrue(report.backingShare() >= 0.01D, report.name() + " backing share " + report.backingShare());
            assertTrue(report.blockShare() >= 0.02D, report.name() + " block share " + report.blockShare());
        }
        assertTrue(total <= TOTAL_BUDGET_BYTES, "nine plates deflate to " + total + " bytes: " + detail);
        assertTrue(palette.size() >= 40, "session palette of " + palette.size() + " states is not representative");
    }
}
