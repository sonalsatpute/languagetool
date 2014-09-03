/* LanguageTool, a natural language style checker 
 * Copyright (C) 2014 Daniel Naber (http://www.danielnaber.de)
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301
 * USA
 */
package org.languagetool.dev.eval;

import org.apache.tika.io.IOUtils;
import org.languagetool.JLanguageTool;
import org.languagetool.language.BritishEnglish;
import org.languagetool.languagemodel.LanguageModel;
import org.languagetool.languagemodel.LuceneLanguageModel;
import org.languagetool.rules.ConfusionProbabilityRule;
import org.languagetool.rules.ConfusionSetLoader;
import org.languagetool.rules.Rule;
import org.languagetool.rules.RuleMatch;
import org.languagetool.rules.en.EnglishConfusionProbabilityRule;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * Runs LanguageTool's confusion rule on Wikipedia-extracted sentences that we assume to be correct.
 * @since 2.6
 */
class RealWordFalseAlarmEvaluator {

  private static final int MAX_SENTENCES = 1000;
  private static final int MAX_ERROR_DISPLAY = 50;
  
  private final JLanguageTool langTool;
  private final ConfusionProbabilityRule confusionRule;
  private final Map<String,ConfusionProbabilityRule.ConfusionSet> confusionSet;
  
  private int globalSentenceCount;
  private int globalRuleMatches;

  RealWordFalseAlarmEvaluator(File languageModelIndexDir) throws IOException {
    ConfusionSetLoader confusionSetLoader =  new ConfusionSetLoader();
    InputStream inputStream = JLanguageTool.getDataBroker().getFromRulesDirAsStream("homophonedb.txt");
    confusionSet = confusionSetLoader.loadConfusionSet(inputStream, null);
    langTool = new JLanguageTool(new BritishEnglish());
    //langTool.activateDefaultPatternRules();
    List<Rule> rules = langTool.getAllActiveRules();
    for (Rule rule : rules) {
      langTool.disableRule(rule.getId());
    }
    LanguageModel languageModel = new LuceneLanguageModel(languageModelIndexDir);
    confusionRule = new EnglishConfusionProbabilityRule(JLanguageTool.getMessageBundle(), languageModel);
    langTool.addRule(confusionRule);
  }

  void run(File dir) throws IOException {
    System.out.println("grep for '^DATA;' to get results in CVS format:");
    System.out.println("DATA;word;sentence_count;errors_found;errors_percent");
    File[] files = dir.listFiles();
    //noinspection ConstantConditions
    int fileCount = 1;
    for (File file : files) {
      if (!file.getName().endsWith(".txt")) {
        System.out.println("Ignoring " + file + ", does not match *.txt");
        continue;
      }
      try (FileInputStream fis = new FileInputStream(file)) {
        System.out.println("===== Working on " + file.getName() + " (" + fileCount + "/" + files.length + ") =====");
        checkLines(IOUtils.readLines(fis), file.getName().replace(".txt", ""));
        fileCount++;
      }
    }
    System.out.println(globalSentenceCount + " sentences checked");
    System.out.println(globalRuleMatches + " errors found");
  }

  private void checkLines(List<String> lines, String name) throws IOException {
    ConfusionProbabilityRule.ConfusionSet subConfusionSet = confusionSet.get(name);
    if (subConfusionSet == null) {
      throw new RuntimeException("No confusion set found for '" + name 
              + "' - please make sure file names in sentence directory are like 'their.txt' and 'there.txt'");
    }
    confusionRule.setConfusionSet(subConfusionSet);
    int sentenceCount = 0;
    int ruleMatches = 0;
    for (String line : lines) {
      List<RuleMatch> matches = langTool.check(line);
      sentenceCount++;
      globalSentenceCount++;
      if (matches.size() > 0) {
        Set<String> suggestions = new HashSet<>();
        for (RuleMatch match : matches) {
          //System.out.println("    " + match + ": " + match.getSuggestedReplacements());
          suggestions.addAll(match.getSuggestedReplacements());
          ruleMatches++;
          globalRuleMatches++;
        }
        if (ruleMatches <= MAX_ERROR_DISPLAY) {
          System.out.println("[" + name + "] " + line + " => " + suggestions);
        }
      }
      if (sentenceCount > MAX_SENTENCES) {
        System.out.println("Max sentences (" + MAX_SENTENCES + ") reached, stopping");
        break;
      }
    }
    System.out.println(sentenceCount + " sentences checked");
    System.out.println(ruleMatches + " errors found");
    float percentage = ((float)ruleMatches/(float)sentenceCount*100);
    System.out.printf("%.2f%% of sentences have a match\n", percentage);
    System.out.printf(Locale.ENGLISH, "DATA;%s;%d;%d;%.2f\n\n", name, sentenceCount, ruleMatches, percentage);
  }

  public static void main(String[] args) throws IOException {
    if (args.length != 2) {
      System.out.println("Usage: " + RealWordFalseAlarmEvaluator.class.getSimpleName() + " <languageModel> <sentenceDirectory>");
      System.out.println("   <languageModel> is a Lucene index with ngram frequency information");
      System.out.println("   <sentenceDirectory> is a directory with filenames like 'xx.txt' where 'xx' is the homophone");
      System.exit(1);
    }
    RealWordFalseAlarmEvaluator evaluator = new RealWordFalseAlarmEvaluator(new File(args[0]));
    File dir = new File(args[1]);
    if (!dir.isDirectory()) {
      throw new RuntimeException("Not a directory: " + dir);
    }
    evaluator.run(dir);
  }

}
