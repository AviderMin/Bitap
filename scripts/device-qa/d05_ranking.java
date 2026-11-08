import com.bitap.keyboard.core.PinyinEngine;
import com.bitap.keyboard.core.Candidate;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import kotlin.sequences.SequencesKt;

public class D05Ranking {
    static String norm(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || (c >= 0x3400 && c <= 0x4DBF) || (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0xF900 && c <= 0xFAFF)) b.append(c);
        }
        return b.toString();
    }
    public static void main(String[] args) throws Exception {
        String dictPath = args[0], devPath = args[1], label = args[2], outPath = args[3];
        List<String> dictLines = Files.readAllLines(Paths.get(dictPath), StandardCharsets.UTF_8);
        PinyinEngine engine = PinyinEngine.Companion.fromLines(SequencesKt.asSequence(dictLines.iterator()), null);
        List<String> devLines = Files.readAllLines(Paths.get(devPath), StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder("label\t" + label + "\n");
        for (String raw : devLines) {
            String line = raw.replace("\uFEFF", "").trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\t");
            if (parts.length < 4) continue;
            String id = parts[0], category = parts[1], input = parts[2];
            if (!category.startsWith("natural_")) continue;
            int coverage = 0;
            for (int i = 0; i < input.length(); i++) { char c = input.charAt(i); if (Character.isLetterOrDigit(c) || c == '\'') coverage++; }
            if (coverage > 64 || coverage < 1) continue;
            String[] expected = parts[3].split("\\|");
            List<Candidate> cands = engine.search(input, 64, new kotlin.jvm.functions.Function0<Boolean>() { public Boolean invoke() { return Boolean.TRUE; } });
            int rank = -1;
            for (int i = 0; i < cands.size(); i++) {
                String w = norm(cands.get(i).getWord());
                boolean hit = false;
                for (String e : expected) { if (w.equals(norm(e))) { hit = true; break; } }
                if (hit) { rank = i; break; }
            }
            StringBuilder top = new StringBuilder();
            for (int i = 0; i < Math.min(8, cands.size()); i++) { if (i > 0) top.append("|"); top.append(cands.get(i).getWord()); }
            out.append("RANK\t").append(id).append("\t").append(category).append("\t").append(coverage).append("\t").append(rank).append("\t").append(parts[3]).append("\t").append(top).append("\n");
        }
        Files.write(Paths.get(outPath), out.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("wrote " + outPath);
    }
}
