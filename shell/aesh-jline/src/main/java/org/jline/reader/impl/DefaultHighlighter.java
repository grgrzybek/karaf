/*
 * Copyright (c) 2002-2016, the original author or authors.
 *
 * This software is distributable under the BSD license. See the terms of the
 * BSD license in the documentation provided with this software.
 *
 * http://www.opensource.org/licenses/bsd-license.php
 */
package org.jline.reader.impl;

import java.util.regex.Pattern;

import org.jline.reader.Highlighter;
import org.jline.reader.LineReader;
import org.jline.utils.AttributedString;

public class DefaultHighlighter implements Highlighter {
    private Pattern errorPattern;
    private int errorIndex = -1;

    @Override
    public AttributedString highlight(LineReader reader, String buffer) {
        return AttributedString.fromAnsi(buffer);
    }

    @Override
    public void setErrorPattern(Pattern errorPattern) {
        this.errorPattern = errorPattern;
    }

    @Override
    public void setErrorIndex(int errorIndex) {
        this.errorIndex = errorIndex;
    }

}
