package com.bosch.demo.docgrounding.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;

@Service
public class HtmlToTextService {
    public String toPlainText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        Document document = Jsoup.parse(html);
        document.select("script,style,noscript").remove();
        return document.text().replaceAll("\\s+", " ").trim();
    }
}
