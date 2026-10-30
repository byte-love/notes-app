package cn.bytelove.notes.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 评论的脏话挡板。词表在 resources/sensitive-words.txt，一行一个。
 * 做法参考 houbb/sensitive-word 的 DFA：把词建成前缀树，扫一遍文本就能找到所有命中，
 * 命中的字替换成 *（不整条拦掉，免得作者自己也没法改）。
 */
@Service
public class CommentFilter {

    private static final class Node {
        final java.util.Map<Character, Node> next = new java.util.HashMap<>();
        boolean end;
    }

    private final Node root = new Node();
    private final List<String> words = new ArrayList<>();

    public CommentFilter() {
        ClassPathResource res = new ClassPathResource("sensitive-words.txt");
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(res.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                String w = line.trim().toLowerCase(Locale.ROOT);
                if (w.isEmpty() || w.startsWith("#")) {
                    continue;
                }
                add(w);
                words.add(w);
            }
        } catch (IOException e) {
            // 词表丢了也不该让评论挂掉，最多不挡
        }
    }

    private void add(String word) {
        Node node = root;
        for (int i = 0; i < word.length(); i++) {
            node = node.next.computeIfAbsent(word.charAt(i), k -> new Node());
        }
        node.end = true;
    }

    /** 命中就把它替换成同样长度的 *，返回处理后的文本。 */
    public String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        char[] chars = text.toCharArray();
        String lower = text.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lower.length(); i++) {
            Node node = root;
            int j = i;
            int hitEnd = -1;
            while (j < lower.length()) {
                node = node.next.get(lower.charAt(j));
                if (node == null) {
                    break;
                }
                j++;
                if (node.end) {
                    hitEnd = j;
                }
            }
            if (hitEnd > i) {
                for (int k = i; k < hitEnd; k++) {
                    chars[k] = '*';
                }
                i = hitEnd - 1;
            }
        }
        return new String(chars);
    }

    public boolean hit(String text) {
        return text != null && !text.equals(mask(text));
    }

    public int size() {
        return words.size();
    }
}
