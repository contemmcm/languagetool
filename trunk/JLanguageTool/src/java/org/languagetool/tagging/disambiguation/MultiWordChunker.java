/* LanguageTool, a natural language style checker 
 * Copyright (C) 2007 Daniel Naber (http://www.danielnaber.de)
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

package org.languagetool.tagging.disambiguation;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.languagetool.AnalyzedSentence;
import org.languagetool.AnalyzedToken;
import org.languagetool.AnalyzedTokenReadings;
import org.languagetool.JLanguageTool;

/**
 * Multiword tagger-chunker.
 *
 * @author Marcin Miłkowski
 */
public class MultiWordChunker implements Disambiguator {

  private Map<String, Integer> mStartSpace;
  private Map<String, Integer> mStartNoSpace;
  private Map<String, String> mFull;

  private final String filename;

  public MultiWordChunker(final String filename) {
    super();
    this.filename = filename;
  }
  
  /*
  * Lazy init, thanks to Artur Trzewik
  */
  private void lazyInit() throws IOException {

    if (mStartSpace != null) {
      return;
    }

    mStartSpace = new HashMap<String, Integer>();
    mStartNoSpace = new HashMap<String, Integer>();
    mFull = new HashMap<String, String>();

    final List<String> posTokens = loadWords(JLanguageTool.getDataBroker().getFromResourceDirAsStream(filename));
    for (String posToken : posTokens) {
      final String[] tokenAndTag = posToken.split("\t");
      final boolean containsSpace = tokenAndTag[0].indexOf(' ') > 0;
      String firstToken = "";
      final String[] firstTokens;
      if (!containsSpace) {
        firstTokens = new String[tokenAndTag[0].length()];
        firstToken = tokenAndTag[0].substring(0, 1);
        for (int i = 1; i < tokenAndTag[0].length(); i++) {
          firstTokens[i] = tokenAndTag[0].substring(0 + (i - 1), i);
        }
        if (mStartNoSpace.containsKey(firstToken)) {
          if (mStartNoSpace.get(firstToken) < firstTokens.length) {
            mStartNoSpace.put(firstToken, firstTokens.length);
          }
        } else {
          mStartNoSpace.put(firstToken, firstTokens.length);
        }
      } else {
        firstTokens = tokenAndTag[0].split(" ");
        firstToken = firstTokens[0];

        if (mStartSpace.containsKey(firstToken)) {
          if (mStartSpace.get(firstToken) < firstTokens.length) {
            mStartSpace.put(firstToken, firstTokens.length);
          }
        } else {
          mStartSpace.put(firstToken, firstTokens.length);
        }
      }
      mFull.put(tokenAndTag[0], tokenAndTag[1]);
    }
  }

  /**
   * Implements multiword POS tags, e.g., &lt;ELLIPSIS&gt; for ellipsis (...)
   * start, and &lt;/ELLIPSIS&gt; for ellipsis end.
   *
   * @param input
   *          The tokens to be chunked.
   * @return AnalyzedSentence with additional markers.
   * @throws IOException
   */
  @Override
  public final AnalyzedSentence disambiguate(final AnalyzedSentence input) throws IOException {

      lazyInit();

      final AnalyzedTokenReadings[] anTokens = input.getTokens();
      final AnalyzedTokenReadings[] output = anTokens;

      for (int i = 0; i < anTokens.length; i++) {
          final String tok = output[i].getToken();
          final StringBuilder tokens = new StringBuilder();

          int finalLen = 0;
          if (mStartSpace.containsKey(tok)) {
              final int len = mStartSpace.get(tok);
              int j = i;
              int lenCounter = 0;
              while (j < anTokens.length) {
                  if (!anTokens[j].isWhitespace()) {
                      tokens.append(anTokens[j].getToken());
                      final String toks = tokens.toString();
                      if (mFull.containsKey(toks)) {
                          output[i] = prepareNewReading(toks, tok, output[i], false);
                          output[finalLen] = prepareNewReading(toks, anTokens[finalLen].getToken(), 
                                  output[finalLen], true);              
                      }
                      lenCounter++;
                      if (lenCounter == len) {
                          break;
                      }
                      tokens.append(' ');
                  }
                  j++;
                  finalLen = j;
              }
          }

          if (mStartNoSpace.containsKey(tok)) {
              final int len = mStartNoSpace.get(tok);
              if (i + len <= anTokens.length) {
                  for (int j = i; j < i + len; j++) {
                      tokens.append(anTokens[j].getToken());
                      final String toks = tokens.toString();
                      if (mFull.containsKey(toks)) {
                          output[i] = prepareNewReading(toks, tok, output[i], false);
                          output[i + len - 1] = prepareNewReading(toks, anTokens
                                  [i + len - 1].getToken(), output[i + len -1], true);                          
                          
                      }
                  }
              }
          }
      }
      return new AnalyzedSentence(output);
  }
  
  
  private AnalyzedTokenReadings prepareNewReading(final String tokens, final String tok,
             final AnalyzedTokenReadings token, final boolean isLast) {
      StringBuilder sb = new StringBuilder();
      sb.append("<");
      if (isLast) {
          sb.append("/");
      }
      sb.append(mFull.get(tokens));
      sb.append(">");
      final AnalyzedToken tokenStart = new AnalyzedToken(tok, sb.toString(), tokens);
      return setAndAnnotate(token, tokenStart);      
  }
  
  private AnalyzedTokenReadings setAndAnnotate(final AnalyzedTokenReadings oldReading, 
          final AnalyzedToken newReading) {      
      final String old = oldReading.toString();
      final String prevAnot =  oldReading.getHistoricalAnnotations();
      AnalyzedTokenReadings newAtr = new AnalyzedTokenReadings(oldReading.getReadings(), 
              oldReading.getStartPos());
      newAtr.addReading(newReading);
      newAtr.setHistoricalAnnotations(
              annotateToken(prevAnot, old, newAtr.toString()));
      return newAtr;
  }
  
  private String annotateToken(final String prevAnot, final String oldReading, final String newReading) {
      StringBuilder sb = new StringBuilder();
      sb.append(prevAnot);
      sb.append("\nMULTIWORD_CHUNKER: ");
      sb.append(oldReading);
      sb.append(" -> ");
      sb.append(newReading);
      return sb.toString();
  }
  

  private List<String> loadWords(final InputStream file) throws IOException {
    InputStreamReader isr = null;
    BufferedReader br = null;
    final List<String> lines = new ArrayList<String>();
    try {
      isr = new InputStreamReader(file, "UTF-8");
      br = new BufferedReader(isr);
      String line;

      while ((line = br.readLine()) != null) {
        line = line.trim();
        if (line.length() < 1) {
          continue;
        }
        if (line.charAt(0) == '#') { // ignore comments
          continue;
        }
        lines.add(line);
      }

    } finally {
      if (br != null) {
        br.close();
      }
      if (isr != null) {
        isr.close();
      }
    }
    return lines;
  }

}