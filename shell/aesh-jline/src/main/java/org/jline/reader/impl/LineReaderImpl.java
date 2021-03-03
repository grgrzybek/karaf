/*
 * JBoss, Home of Professional Open Source
 * Copyright 2014 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @authors tag. All rights reserved.
 * See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jline.reader.impl;

import org.aesh.readline.ConsoleBuffer;
import org.aesh.readline.InputProcessor;
import org.aesh.readline.Prompt;
import org.aesh.readline.Readline;
import org.aesh.readline.action.Action;
import org.aesh.readline.action.mappings.Enter;
import org.aesh.readline.completion.CompletionHandler;
import org.aesh.readline.editing.EditMode;
import org.aesh.readline.editing.EditModeBuilder;
import org.aesh.readline.terminal.Key;
import org.aesh.utils.Config;
import org.jline.keymap.BindingReader;
import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.Buffer;
import org.jline.reader.Completer;
import org.jline.reader.EOFError;
import org.jline.reader.Editor;
import org.jline.reader.EndOfFileException;
import org.jline.reader.Expander;
import org.jline.reader.Highlighter;
import org.jline.reader.History;
import org.jline.reader.LineReader;
import org.jline.reader.MaskingCallback;
import org.jline.reader.ParsedLine;
import org.jline.reader.Parser;
import org.jline.reader.SyntaxError;
import org.jline.reader.UserInterruptException;
import org.jline.reader.Widget;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Attributes;
import org.jline.terminal.MouseEvent;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.aesh.readline.util.Parser.fromCodePoints;
import static org.aesh.readline.util.Parser.toCodePoints;

/**
 * @author <a href="mailto:gnodet@gmail.com">Guillaume Nodet</a>
 */
public class LineReaderImpl implements LineReader {

    private final Terminal terminal;
    private final String appName;
    private final Map<String, Object> variables;

    private Parser parser = new DefaultParser();
    private Expander expander = new DefaultExpander();
    private History history = new DefaultHistory();
    private Highlighter highlighter = new DefaultHighlighter();

    private CompletionHandler completionHandler;

    private final Map<Option, Boolean> options = new HashMap<>();

    private EditMode editMode;

    private Prompt prompt;
    private ParsedLine parsedLine;

    /*
     * execute commands from commandsBuffer
     */
    protected List<String> commandsBuffer = new ArrayList<>();
    // Reading buffers
    protected final BindingReader bindingReader;

    protected String tailTip = "";
    protected SuggestionType autosuggestion = SuggestionType.NONE;

    public LineReaderImpl(Terminal terminal,
                          String appName,
                          Map<String, Object> variables) {
        Objects.requireNonNull(terminal);
        this.terminal = terminal;
        this.appName = appName;
        this.variables = variables;

        this.editMode = EditModeBuilder.builder().create();
        Action enter = new Enter() {
            @Override
            public void accept(InputProcessor inputProcessor) {
                acceptLine(inputProcessor);
            }
        };
        this.editMode.addAction(Key.CTRL_J, enter);
        this.editMode.addAction(Key.CTRL_M, enter);
        this.editMode.addAction(Key.ENTER, enter);
        this.editMode.addAction(Key.ENTER_2, enter);

        bindingReader = new BindingReader(terminal.reader());
    }

    public void setParser(Parser parser) {
        this.parser = parser;
    }

    public void setExpander(Expander expander) {
        this.expander = expander;
    }

    public void setHistory(History history) {
        this.history = history;
        history.attach(this);
    }

    public void setCompleter(Completer completer) {
        this.completionHandler = new CompletionHandlerImpl(this, completer);
    }

    public void setHighlighter(Highlighter highlighter) {
    }

    private String readInput(String buffer) {
        final String[] out = new String[1];
        Attributes attr = terminal.enterRawMode();
        parsedLine = null;
        TerminalConnection connection = new TerminalConnection(terminal, editMode);
        Readline readline = new Readline(editMode, new HistoryWrapper(history), completionHandler);
        try {
            readline.readline(connection, prompt, line -> {
                connection.close();
                out[0] = line;
            }, null);
            connection.openBlocking(buffer);
        } finally {
            terminal.setAttributes(attr);
        }
        if (out[0] == null) {
            throw new EndOfFileException();
        }
        return out[0];
    }

    private void acceptLine(InputProcessor inputProcessor) {
        ConsoleBuffer consoleBuffer = inputProcessor.buffer();
        String str = fromCodePoints(consoleBuffer.buffer().multiLine());

        parsedLine = null;
        String exp = str;
        if (!isSet(Option.DISABLE_EVENT_EXPANSION)) {
            try {
                exp = expander.expandHistory(getHistory(), str);
                if (!exp.equals(str)) {
                    consoleBuffer.replace(exp);
                    if (isSet(Option.HISTORY_VERIFY)) {
                        return;
                    }
                }
            } catch (IllegalArgumentException e) {
                // Ignore
            }
        }
        try {
            parsedLine = parser.parse(exp, consoleBuffer.buffer().multiCursor(), Parser.ParseContext.ACCEPT_LINE);
        } catch (EOFError e) {
            consoleBuffer.buffer().setMultiLine(true);
            consoleBuffer.buffer().updateMultiLineBuffer();
            consoleBuffer.writeOut(Config.CR);
            consoleBuffer.drawLine();
            return;
        } catch (SyntaxError e) {
            // do nothing
        }
        callWidget(CALLBACK_FINISH);
        finishBuffer(inputProcessor);
        consoleBuffer.buffer().reset();
    }

    protected void finishBuffer(InputProcessor inputProcessor) {
        ConsoleBuffer consoleBuffer = inputProcessor.buffer();
        String str = fromCodePoints(consoleBuffer.buffer().multiLine());
        String historyLine = str;

        if (!isSet(Option.DISABLE_EVENT_EXPANSION)) {
            StringBuilder sb = new StringBuilder();
            boolean escaped = false;
            for (int i = 0; i < str.length(); i++) {
                char ch = str.charAt(i);
                if (escaped) {
                    escaped = false;
                    if (ch != '\n') {
                        sb.append(ch);
                    }
                } else if (ch == '\\') {
                    escaped = true;
                } else {
                    sb.append(ch);
                }
            }
            str = sb.toString();
        }

        // we only add it to the history if the buffer is not empty
        // and if mask is null, since having a mask typically means
        // the string was a password. We clear the mask after this call
        if (str.length() > 0 && !prompt.isMasking()) {
            history.add(Instant.now(), historyLine);
        }
        history.moveToEnd();
        inputProcessor.setReturnValue(toCodePoints(str));
    }

    @Override
    public Map<String, KeyMap<Binding>> defaultKeyMaps() {
        return Collections.emptyMap();
    }

    public String readLine() throws UserInterruptException, EndOfFileException {
        return readLine(null, null, (Character) null, null);
    }

    /**
     * Read the next line with the specified character mask. If null, then
     * characters will be echoed. If 0, then no characters will be echoed.
     */
    public String readLine(Character mask) throws UserInterruptException, EndOfFileException {
        return readLine(null, null, mask, null);
    }

    public String readLine(String prompt) throws UserInterruptException, EndOfFileException {
        return readLine(prompt, null, (Character) null, null);
    }

    /**
     * Read a line from the <i>in</i> {@link InputStream}, and return the line
     * (without any trailing newlines).
     *
     * @param prompt    The prompt to issue to the terminal, may be null.
     * @return          A line that is read from the terminal, or null if there was null input (e.g., <i>CTRL-D</i>
     *                  was pressed).
     */
    public String readLine(String prompt, Character mask) throws UserInterruptException, EndOfFileException {
        return readLine(prompt, null, mask, null);
    }

    /**
     * Read a line from the <i>in</i> {@link InputStream}, and return the line
     * (without any trailing newlines).
     *
     * @param prompt    The prompt to issue to the terminal, may be null.
     * @return          A line that is read from the terminal, or null if there was null input (e.g., <i>CTRL-D</i>
     *                  was pressed).
     */
    public String readLine(String prompt, Character mask, String buffer) throws UserInterruptException, EndOfFileException {
        return readLine(prompt, null, mask, buffer);
    }

    @Override
    public String readLine(String prompt, String rightPrompt, Character character, String buffer) throws UserInterruptException, EndOfFileException {
        // TODO: Right prompt support
        AttributedString s = AttributedString.fromAnsi(prompt);
        this.prompt = new Prompt(s.toString(), s.toAnsi(terminal), character);
        return readInput(buffer);
    }

    @Override
    public String readLine(String prompt, String rightPrompt, MaskingCallback maskingCallback, String buffer) throws UserInterruptException, EndOfFileException {
        // TODO: Right prompt support
        AttributedString s = AttributedString.fromAnsi(prompt);
        this.prompt = new Prompt(s.toString(), s.toAnsi(terminal), null);
        return readInput(buffer);
    }

    @Override
    public LineReader variable(String name, Object value) {
        variables.put(name, value);
        return this;
    }

    @Override
    public Map<String, Object> getVariables() {
        return variables;
    }

    @Override
    public Object getVariable(String s) {
        return variables.get(s);
    }

    @Override
    public void setVariable(String s, Object o) {
        variables.put(s, o);
    }

    @Override
    public LineReader option(Option option, boolean value) {
        options.put(option, value);
        return this;
    }

    @Override
    public boolean isSet(Option option) {
        Boolean b = options.get(option);
        return b != null ? b : option.isDef();
    }

    @Override
    public void setOpt(Option option) {
        options.put(option, Boolean.TRUE);
    }

    @Override
    public void unsetOpt(Option option) {
        options.put(option, Boolean.FALSE);
    }

    @Override
    public void addCommandsInBuffer(Collection<String> commands) {
        commandsBuffer.addAll(commands);
    }

    @Override
    public void editAndAddInBuffer(File file) throws Exception {
        Constructor<?> ctor = Class.forName("org.jline.builtins.Nano").getConstructor(Terminal.class, File.class);
        Editor editor = (Editor) ctor.newInstance(terminal, new File(file.getParent()));
        editor.setRestricted(true);
        editor.open(Arrays.asList(file.getName()));
        editor.run();
        BufferedReader br = new BufferedReader(new FileReader(file));
        String line;
        commandsBuffer.clear();
        while ((line = br.readLine()) != null) {
            commandsBuffer.add(line);
        }
        br.close();
    }

    @Override
    public Terminal getTerminal() {
        return terminal;
    }

    @Override
    public ParsedLine getParsedLine() {
        return parsedLine;
    }

    @Override
    public String getLastBinding() {
        return bindingReader.getLastBinding();
    }

    @Override
    public History getHistory() {
        return history;
    }

    @Override
    public Parser getParser() {
        return parser;
    }

    @Override
    public void callWidget(String s) {
    }

    @Override
    public Map<String, Widget> getWidgets() {
        return Collections.emptyMap();
    }

    @Override
    public Map<String, Widget> getBuiltinWidgets() {
        return Collections.emptyMap();
    }

    @Override
    public Buffer getBuffer() {
        return null;
    }

    @Override
    public void setAutosuggestion(SuggestionType type) {
        this.autosuggestion = type;
    }

    @Override
    public SuggestionType getAutosuggestion() {
        return autosuggestion;
    }

    @Override
    public String getTailTip() {
        return tailTip;
    }

    @Override
    public void setTailTip(String tailTip) {
        this.tailTip = tailTip;
    }

    @Override
    public void runMacro(String s) {
    }

    @Override
    public Highlighter getHighlighter() {
        return highlighter;
    }

    @Override
    public Expander getExpander() {
        return expander;
    }

    @Override
    public Map<String, KeyMap<Binding>> getKeyMaps() {
        return Collections.emptyMap();
    }

    @Override
    public String getSearchTerm() {
        return null;
    }

    @Override
    public RegionType getRegionActive() {
        return null;
    }

    @Override
    public int getRegionMark() {
        return 0;
    }

    @Override
    public MouseEvent readMouseEvent() {
        return null;
    }

    @Override
    public String getKeyMap() {
        return null;
    }

    @Override
    public boolean setKeyMap(String name) {
        return false;
    }

    @Override
    public KeyMap<Binding> getKeys() {
        return null;
    }

    @Override
    public void printAbove(String str) {
    }

    @Override
    public void printAbove(AttributedString str) {
    }

    @Override
    public boolean isReading() {
        return false;
    }

    @Override
    public String getAppName() {
        return null;
    }

}
