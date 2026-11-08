import com.bitap.keyboard.core.PinyinEngine;
import com.bitap.keyboard.core.Candidate;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import kotlin.sequences.SequencesKt;

public class D05Candidates {
    public static void main(String[] args) throws Exception {
        String dictPath = args[0];
        String devPath = args[1];
        String label = args[2];
        String outPath = args[3];
        List<String> dictLines = Files.readAllLines(Paths.get(dictPath), StandardCharsets.UTF_8);
        PinyinEngine engine = PinyinEngine.Companion.fromLines(SequencesKt.asSequence(dictLines.iterator()), null);
        List<String> devLines = Files.readAllLines(Paths.get(devPath), StandardCharsets.UTF_8);
        int count = 0;
        StringBuilder out = new StringBuilder();
        out.append("label\t").append(label).append("\n");
        for (String raw : devLines) {
            String line = raw.replace("\uFEFF", "").trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\t");
            if (parts.length < 4) continue;
            String id = parts[0], category = parts[1], input = parts[2];
            if (!(category.equals("natural_medium") || category.equals("natural_long"))) continue;
            int coverage = 0;
            for (int i = 0; i < input.length(); i++) {
                char c = input.charAt(i);
                if (Character.isLetterOrDigit(c) || c == '\'') coverage++;
            }
            if (coverage < 24) continue;
            List<Candidate> cands = engine.search(input, 8, new kotlin.jvm.functions.Function0<Boolean>() { public Boolean invoke() { return Boolean.TRUE; } });
            int firstPartial = -1;
            for (int i = 0; i < cands.size(); i++) {
                if (cands.get(i).getConsumedInputLength() < coverage) { firstPartial = i; break; }
            }
            if (firstPartial < 0) continue;
            StringBuilder cs = new StringBuilder();
            for (int i = 0; i < cands.size(); i++) {
                if (i > 0) cs.append("|");
                Candidate c = cands.get(i);
                cs.append(c.getWord()).append("(").append(c.getConsumedInputLength());
                if (c.isCorrection()) cs.append(",corr");
                cs.append(")");
            }
            out.append("CASE\t").append(id).append("\t").append(category).append("\t").append(input.length()).append("\t").append(coverage).append("\t").append(firstPartial).append("\t").append(cs).append("\n");
            count++;
        }
        out.append("TOTAL_VISIBLE_PARTIAL_CASES\t").append(count).append("\n");
        Files.write(Paths.get(outPath), out.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("wrote " + outPath + " cases=" + count);
    }
}
