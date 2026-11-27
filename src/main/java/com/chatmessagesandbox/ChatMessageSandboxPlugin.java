
package com.chatmessagesandbox;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Inject;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import javax.swing.ScrollPaneConstants;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;

import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.FontTypeFace;
import net.runelite.api.GameState;
import net.runelite.api.MessageNode;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetPositionMode;
import net.runelite.api.widgets.WidgetSizeMode;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.ColorJButton;
import net.runelite.client.ui.components.colorpicker.ColorPickerManager;
import net.runelite.client.ui.components.colorpicker.RuneliteColorPicker;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.SwingUtil;
import net.runelite.client.util.Text;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@PluginDescriptor(
    name = "Chat Message Sandbox",
    description = "Adds manually composed messages to the local RuneLite chat renderer"
)
public class ChatMessageSandboxPlugin extends Plugin
{
    private static final Logger log =
        LoggerFactory.getLogger(ChatMessageSandboxPlugin.class);

    /** How many sandbox messages to remember; older ones go back to the game's normal look. */
    private static final int MAX_SANDBOX_MESSAGES = 100;

    /**
     * The sender part of a private message line, "From Name:" or "To Name:", optionally after a
     * "[12:34]" timestamp. Matched against the text with tags (colours, icons) removed.
     * Group 1 is the name.
     */
    private static final Pattern PRIVATE_MESSAGE_SENDER =
        Pattern.compile("^(?:\\[[^\\]]*\\]\\s*)?(?:From|To) (.+):$");

    /** Opening and closing colour tags in chat text, e.g. {@code <col=00ffff>} and {@code </col>}. */
    private static final Pattern COLOR_TAG = Pattern.compile("<col=[0-9a-fA-F]{1,6}>|</col>");

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    private ColorPickerManager colorPickerManager;

    private MessagePanel panel;
    private NavigationButton navButton;

    /** The style chosen in the panel. Written on the Swing thread, read on the client thread. */
    private volatile MessageStyle currentStyle = MessageStyle.DEFAULT;

    /** Set when the style changes, so the split private chat is restyled on the next client tick. */
    private volatile boolean splitRestyleRequested;

    /** Chatbox lines this plugin has changed, with how to put them back. Client thread only. */
    private final StyledWidgets chatboxChanges = new StyledWidgets();

    /** Split private chat lines this plugin has changed, with how to put them back. Client thread only. */
    private final StyledWidgets splitChanges = new StyledWidgets();

    /**
     * Sandbox messages, keyed by {@link #messageKey sender and visible text}. They always use the
     * current panel style. Only touched on the client thread.
     */
    private final Map<String, Boolean> sandboxMessages =
        new LinkedHashMap<String, Boolean>()
        {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest)
            {
                return size() > MAX_SANDBOX_MESSAGES;
            }
        };

    /**
     * IDs of sandbox messages added with "Show timestamp" off. Their timestamp is removed when the
     * game builds their chat line. Only touched on the client thread.
     */
    private final Map<Integer, Boolean> hiddenTimestamps =
        new LinkedHashMap<Integer, Boolean>()
        {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, Boolean> eldest)
            {
                return size() > MAX_SANDBOX_MESSAGES;
            }
        };

    /** Fingerprint of the split private chat as this plugin last left it; 0 if not styled. */
    private int splitAppliedSignature;

    @Override
    protected void startUp()
    {
        // Create the sidebar panel
        panel = new MessagePanel(colorPickerManager, this::onStyleChanged);
        panel.setSendAction(this::sendMessage);
        currentStyle = panel.getStyle();

        // Register the sidebar button
        navButton = NavigationButton.builder()
            .tooltip("Chat Message Sandbox")
            .icon(createIcon())
            .priority(5)
            .panel(panel)
            .build();

        clientToolbar.addNavigation(navButton);

        log.info("Chat Message Sandbox started!");
    }

    @Override
    protected void shutDown()
    {
        // Remove the sidebar button
        if (navButton != null)
        {
            clientToolbar.removeNavigation(navButton);
        }

        panel = null;
        navButton = null;
        currentStyle = MessageStyle.DEFAULT;

        // Put every changed chat line back the way the game drew it
        clientThread.invoke(() ->
        {
            sandboxMessages.clear();
            hiddenTimestamps.clear();
            chatboxChanges.undo();
            splitChanges.undo();
            splitAppliedSignature = 0;
            rebuildChatbox();
        });

        log.info("Chat Message Sandbox stopped!");
    }

    /**
     * Called on the Swing thread whenever a style control in the panel changes. Earlier changes
     * are undone before the new style is applied, so switching sizes never stacks up and going
     * back to the default really does go back to how the game draws it.
     */
    private void onStyleChanged(MessageStyle style)
    {
        currentStyle = style;
        splitRestyleRequested = true;
        clientThread.invoke(this::rebuildChatbox);
    }

    /**
     * Asks the game to lay out the chatbox again, which restyles it via {@link #onScriptPostFired}.
     * Must be called on the client thread.
     */
    private void rebuildChatbox()
    {
        if (client.getGameState() == GameState.LOGGED_IN)
        {
            client.runScript(ScriptID.BUILD_CHATBOX);
        }
    }

    /**
     * Called when the user presses the Add button.
     */
    private void sendMessage()
    {
        String username = panel.getUsername().trim();
        String message = panel.getMessage().trim();
        String time = panel.getTime().trim();

        ChatMessageType type = panel.isOutgoing()
            ? ChatMessageType.PRIVATECHATOUT
            : ChatMessageType.PRIVATECHAT;

        if (username.isEmpty() || message.isEmpty())
        {
            JOptionPane.showMessageDialog(
                panel,
                "Username and message cannot be empty."
            );
            return;
        }

        // Parse the timestamp entered in the panel (only needed when it will be shown)
        final boolean showTimestamp = panel.isShowTimestamp();
        final long timestamp;

        if (!showTimestamp)
        {
            timestamp = System.currentTimeMillis() / 1000;
        }
        else
        {
            try
            {
                ZoneId zone = ZoneId.systemDefault();

                timestamp = LocalDate.now(zone)
                    .atTime(LocalTime.parse(time))
                    .atZone(zone)
                    .toEpochSecond();
            }
            catch (DateTimeParseException ex)
            {
                JOptionPane.showMessageDialog(
                    panel,
                    "Invalid time. Please use HH:mm, for example 15:26."
                );
                return;
            }
        }

        // Escape typed text so "<" and ">" show as written instead of being read as chat tags.
        // Font and colour are not part of the message: they are applied when the line is drawn,
        // so changing the style later changes this message too.
        // Like the game, only incoming messages show the sender's account icon; "To Name:" never has one
        final String iconTag = type == ChatMessageType.PRIVATECHAT ? panel.getIcon().tag() : "";
        final String name = iconTag + Text.escapeJagex(username);
        final String value = Text.escapeJagex(message);
        final String key = messageKey(username, value);

        // Perform game-client modifications on the client thread
        clientThread.invoke(() ->
        {
            if (client.getGameState() != GameState.LOGGED_IN)
            {
                SwingUtilities.invokeLater(() ->
                    JOptionPane.showMessageDialog(
                        null,
                        "You must be logged into the game first."
                    )
                );
                return;
            }

            // Register before adding, so the redraw that follows picks it up
            sandboxMessages.put(key, Boolean.TRUE);
            MessageNode node = addMessage(type, name, value, username, timestamp);
            if (node != null && !showTimestamp)
            {
                hiddenTimestamps.put(node.getId(), Boolean.TRUE);
                client.refreshChat();
            }
        });
    }

    /**
     * Adds a message using RuneLite's native chat renderer.
     */
    private MessageNode addMessage(
        ChatMessageType type,
        String name,
        String message,
        String sender,
        long timestamp
    )
    {
        MessageNode node = client.addChatMessage(
            type,
            name,
            message,
            sender
        );

        if (node != null)
        {
            node.setTimestamp(Math.toIntExact(timestamp));
        }
        return node;
    }

    /**
     * Removes the timestamp from sandbox messages added with "Show timestamp" off.
     *
     * <p>When the game builds a chat line it asks for a prefix, which RuneLite's Chat Timestamps
     * plugin fills with the time. This runs after it (lower priority) and clears that prefix, but
     * only for those sandbox messages.
     */
    @Subscribe(priority = -1)
    public void onScriptCallbackEvent(ScriptCallbackEvent event)
    {
        if (!"chatMessageBuilding".equals(event.getEventName()) || hiddenTimestamps.isEmpty())
        {
            return;
        }

        int messageId = client.getIntStack()[client.getIntStackSize() - 1];
        if (hiddenTimestamps.containsKey(messageId))
        {
            client.getObjectStack()[client.getObjectStackSize() - 1] = "";
        }
    }

    /**
     * Identifies a private message by who it's from/to and the text shown, ignoring colours,
     * icons and letter case in the name. Short texts like "Yo" are only matched on a private
     * message line from the same person, never inside other chat lines.
     */
    private static String messageKey(String sender, String text)
    {
        return Text.standardize(Text.removeTags(sender)) + '\n' + Text.removeTags(text);
    }

    /** Whether any chat line could need restyling with the current style. */
    private boolean hasStyling()
    {
        MessageStyle style = currentStyle;
        return style.changesAnything() && (style.isOverridePrivateMessages() || !sandboxMessages.isEmpty());
    }

    @Subscribe
    public void onScriptPostFired(ScriptPostFired event)
    {
        if (event.getScriptId() != ScriptID.BUILD_CHATBOX)
        {
            return;
        }

        // The game has just laid the chatbox out again: put back anything it didn't reset itself
        chatboxChanges.undo();
        if (hasStyling())
        {
            restyleChatbox();
            chatboxChanges.markApplied();
        }
    }

    @Subscribe
    public void onClientTick(ClientTick event)
    {
        boolean force = splitRestyleRequested;
        splitRestyleRequested = false;
        if (force || hasStyling() || !splitChanges.isEmpty())
        {
            restyleSplitPrivateChat(force);
        }
    }

    /**
     * Restyles private message lines in the chatbox (used when split private chat is off) after
     * the game has laid the chatbox out. Other chat lines keep their font; they only move up if a
     * larger private message below them needs the room. The game resets all of this on its next
     * rebuild, after which this runs again.
     */
    private void restyleChatbox()
    {
        Widget scrollArea = client.getWidget(InterfaceID.Chatbox.SCROLLAREA);
        if (scrollArea == null)
        {
            return;
        }

        TreeMap<Integer, List<Widget>> rows = new TreeMap<>();
        addTextWidgets(rows, scrollArea.getStaticChildren());
        addTextWidgets(rows, scrollArea.getDynamicChildren());
        if (rows.isEmpty())
        {
            return;
        }

        applyRowStyles(rows, chatboxChanges);
    }

    /**
     * Restyles private message lines in the split private chat area above the chatbox.
     *
     * <p>The game redraws this area only when it needs to (new message, expiring lines), so this
     * checks every tick whether the lines changed since they were styled. When they did, or the
     * style changed, earlier changes are undone first so they never stack up.
     */
    private void restyleSplitPrivateChat(boolean force)
    {
        Widget[] children = splitPrivateChatLines();
        if (children == null)
        {
            splitChanges.forget();
            splitAppliedSignature = 0;
            return;
        }

        if (!force && splitSignature(children) == splitAppliedSignature)
        {
            return;
        }

        splitChanges.undo();
        splitAppliedSignature = 0;
        if (!hasStyling())
        {
            return;
        }

        TreeMap<Integer, List<Widget>> rows = new TreeMap<>();
        addTextWidgets(rows, children);
        applyRowStyles(rows, splitChanges);
        splitChanges.markApplied();
        splitAppliedSignature = splitSignature(children);
    }

    private Widget[] splitPrivateChatLines()
    {
        Widget container = client.getWidget(InterfaceID.PmChat.CONTAINER);
        if (container == null || container.isHidden())
        {
            return null;
        }

        Widget[] children = container.getDynamicChildren();
        return children == null || children.length == 0 ? null : children;
    }

    /** A fingerprint of the split private chat lines, to notice when they change. */
    private static int splitSignature(Widget[] children)
    {
        int hash = 17;
        for (Widget widget : children)
        {
            if (widget == null)
            {
                continue;
            }
            hash = 31 * hash + System.identityHashCode(widget);
            hash = 31 * hash + (widget.isHidden() ? 1 : 0);
            hash = 31 * hash + widget.getOriginalX();
            hash = 31 * hash + widget.getOriginalY();
            hash = 31 * hash + widget.getOriginalHeight();
            hash = 31 * hash + widget.getFontId();
            hash = 31 * hash + widget.getTextColor();
            hash = 31 * hash + (widget.getText() == null ? 0 : widget.getText().hashCode());
        }
        return hash == 0 ? 1 : hash;
    }

    /**
     * Groups visible text widgets into rows (one chat line each) by on-screen y.
     * The map is ordered top to bottom.
     */
    private static void addTextWidgets(TreeMap<Integer, List<Widget>> rows, Widget[] widgets)
    {
        if (widgets == null)
        {
            return;
        }

        for (Widget widget : widgets)
        {
            if (widget != null && !widget.isHidden() && widget.getType() == WidgetType.TEXT)
            {
                rows.computeIfAbsent(widget.getRelativeY(), y -> new ArrayList<>()).add(widget);
            }
        }
    }

    /**
     * Applies the style to the private message rows among {@code rows}, bottom row first.
     * A row given a larger font grows upwards, and every row above it moves up to make room.
     *
     * Every widget is recorded in {@code changes} before it is touched, so it can be put back.
     */
    private void applyRowStyles(TreeMap<Integer, List<Widget>> rows, StyledWidgets changes)
    {
        MessageStyle style = currentStyle;
        int shift = 0;

        for (List<Widget> widgets : rows.descendingMap().values())
        {
            PrivateMessageRow pm = PrivateMessageRow.of(widgets);
            boolean styled = pm != null && shouldStyle(pm, style);

            if (styled || shift > 0)
            {
                for (Widget widget : widgets)
                {
                    changes.remember(widget);
                }
            }

            int extra = styled ? applyRowStyle(pm, style) : 0;

            for (Widget widget : widgets)
            {
                if (extra > 0)
                {
                    growUp(widget, extra);
                }
                if (shift > 0)
                {
                    moveUp(widget, shift);
                }
                if (styled || shift > 0)
                {
                    widget.revalidate();
                }
            }

            shift += extra;
        }
    }

    /**
     * Whether a private message row gets the panel style: sandbox messages always do, other
     * private messages only when the override is on.
     */
    private boolean shouldStyle(PrivateMessageRow pm, MessageStyle style)
    {
        if (!style.changesAnything())
        {
            return false;
        }
        return style.isOverridePrivateMessages() || sandboxMessages.containsKey(pm.key);
    }

    /**
     * Sets the font and colour on one private message row, moving the message text so it still
     * starts just after the (possibly wider) "From Name:" part.
     *
     * @return how much taller the row must be for the new font
     */
    private int applyRowStyle(PrivateMessageRow pm, MessageStyle style)
    {
        Widget senderWidget = pm.senderWidget;
        FontTypeFace oldFont = senderWidget.getFont();
        String senderText = senderWidget.getText();

        // Gap the game left between "From Name:" and the message, measured in the old font
        Widget firstMessage = pm.messageWidgets.get(0);
        int gap = oldFont == null ? 0
            : firstMessage.getRelativeX() - (senderWidget.getRelativeX() + oldFont.getTextWidth(senderText));

        int rowHeight = 0;
        for (Widget widget : pm.widgets)
        {
            rowHeight = Math.max(rowHeight, widget.getHeight());
            if (style.getFontId() != ChatFont.GAME_DEFAULT)
            {
                widget.setFontId(style.getFontId());
            }
        }

        if (style.getColor() != null)
        {
            // Colour codes already in the text (from the game or RuneLite's chat colour settings)
            // would win over the widget colour, so they are swapped for the chosen colour
            int rgb = style.getColor().getRGB() & 0xFFFFFF;
            for (Widget widget : pm.messageWidgets)
            {
                widget.setTextColor(rgb);
                String text = widget.getText();
                if (text != null)
                {
                    String plain = COLOR_TAG.matcher(text).replaceAll("");
                    widget.setText(ColorUtil.wrapWithColorTag(plain, style.getColor()));
                }
            }
        }

        if (style.getFontId() == ChatFont.GAME_DEFAULT)
        {
            return 0;
        }

        FontTypeFace newFont = senderWidget.getFont();
        if (newFont == null)
        {
            return 0;
        }

        // Start the message right after the sender in the new font, and let it use the rest of the line
        if (oldFont != null && firstMessage != senderWidget)
        {
            int messageX = senderWidget.getRelativeX() + newFont.getTextWidth(senderText) + Math.max(gap, 0);
            int lineEnd = firstMessage.getRelativeX() + firstMessage.getWidth();
            Widget parent = firstMessage.getParent();
            if (parent != null)
            {
                lineEnd = Math.max(lineEnd, parent.getWidth());
            }

            firstMessage.setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
            firstMessage.setWidthMode(WidgetSizeMode.ABSOLUTE);
            firstMessage.setOriginalX(messageX);
            firstMessage.setOriginalWidth(Math.max(1, lineEnd - messageX));
        }

        int extra = Math.max(0, ChatFont.lineHeight(style.getFontId()) - rowHeight);
        log.debug("Sandbox row '{}' height={} font={} extra={} gap={}",
            pm.sender, rowHeight, style.getFontId(), extra, gap);
        return extra;
    }

    /** Makes a widget taller while keeping its bottom edge where it is. */
    private static void growUp(Widget widget, int amount)
    {
        widget.setHeightMode(WidgetSizeMode.ABSOLUTE);
        widget.setOriginalHeight(widget.getHeight() + amount);
        if (widget.getYPositionMode() != WidgetPositionMode.ABSOLUTE_BOTTOM)
        {
            widget.setOriginalY(widget.getOriginalY() - amount);
        }
    }

    /** Moves a widget up on screen (down for a negative amount), whichever edge it is measured from. */
    private static void moveUp(Widget widget, int amount)
    {
        boolean fromBottom = widget.getYPositionMode() == WidgetPositionMode.ABSOLUTE_BOTTOM;
        widget.setOriginalY(widget.getOriginalY() + (fromBottom ? amount : -amount));
    }

    /**
     * Remembers what chat line widgets looked like before this plugin changed them, so the
     * changes can be undone exactly.
     *
     * <p>For each widget it keeps the original values and the values this plugin set. When undoing,
     * a value is put back only if it still holds what this plugin set; if the game has since set
     * it to something new (a new message moved the lines, say), the game's value is kept.
     */
    private static final class StyledWidgets
    {
        private static final int X = 0, Y = 1, WIDTH = 2, HEIGHT = 3, X_MODE = 4, Y_MODE = 5,
            WIDTH_MODE = 6, HEIGHT_MODE = 7, FONT = 8, COLOR = 9, FIELDS = 10;

        /** One widget's look before and after this plugin changed it. */
        private static final class Change
        {
            private final int[] original;
            private final String originalText;
            private int[] applied;
            private String appliedText;

            private Change(Widget widget)
            {
                original = read(widget);
                originalText = widget.getText();
            }
        }

        private final Map<Widget, Change> changed = new IdentityHashMap<>();

        boolean isEmpty()
        {
            return changed.isEmpty();
        }

        /** Records a widget's current look, the first time it is about to be changed. */
        void remember(Widget widget)
        {
            changed.computeIfAbsent(widget, Change::new);
        }

        /** Records what every remembered widget looks like now that the changes are made. */
        void markApplied()
        {
            for (Map.Entry<Widget, Change> entry : changed.entrySet())
            {
                entry.getValue().applied = read(entry.getKey());
                entry.getValue().appliedText = entry.getKey().getText();
            }
        }

        /** Puts back every value this plugin changed that the game hasn't changed since. */
        void undo()
        {
            for (Map.Entry<Widget, Change> entry : changed.entrySet())
            {
                Change change = entry.getValue();
                if (change.applied == null)
                {
                    continue;
                }

                Widget widget = entry.getKey();
                int[] current = read(widget);
                int[] target = new int[FIELDS];
                for (int i = 0; i < FIELDS; i++)
                {
                    target[i] = current[i] == change.applied[i] ? change.original[i] : current[i];
                }
                write(widget, target);

                String text = widget.getText();
                if (text != null && text.equals(change.appliedText))
                {
                    widget.setText(change.originalText);
                }

                widget.revalidate();
            }
            changed.clear();
        }

        /** Drops all records without changing anything, e.g. when the lines no longer exist. */
        void forget()
        {
            changed.clear();
        }

        private static int[] read(Widget widget)
        {
            int[] values = new int[FIELDS];
            values[X] = widget.getOriginalX();
            values[Y] = widget.getOriginalY();
            values[WIDTH] = widget.getOriginalWidth();
            values[HEIGHT] = widget.getOriginalHeight();
            values[X_MODE] = widget.getXPositionMode();
            values[Y_MODE] = widget.getYPositionMode();
            values[WIDTH_MODE] = widget.getWidthMode();
            values[HEIGHT_MODE] = widget.getHeightMode();
            values[FONT] = widget.getFontId();
            values[COLOR] = widget.getTextColor();
            return values;
        }

        private static void write(Widget widget, int[] values)
        {
            widget.setXPositionMode(values[X_MODE]);
            widget.setYPositionMode(values[Y_MODE]);
            widget.setWidthMode(values[WIDTH_MODE]);
            widget.setHeightMode(values[HEIGHT_MODE]);
            widget.setOriginalX(values[X]);
            widget.setOriginalY(values[Y]);
            widget.setOriginalWidth(values[WIDTH]);
            widget.setOriginalHeight(values[HEIGHT]);
            widget.setFontId(values[FONT]);
            widget.setTextColor(values[COLOR]);
        }
    }

    /**
     * One chat line that is a private message: the "From Name:" / "To Name:" widget plus the
     * widget(s) holding the message text.
     */
    private static final class PrivateMessageRow
    {
        private final List<Widget> widgets;
        private final Widget senderWidget;
        private final List<Widget> messageWidgets;
        private final String sender;
        private final String key;

        private PrivateMessageRow(List<Widget> widgets, Widget senderWidget, List<Widget> messageWidgets,
            String sender, String key)
        {
            this.widgets = widgets;
            this.senderWidget = senderWidget;
            this.messageWidgets = messageWidgets;
            this.sender = sender;
            this.key = key;
        }

        /** The private message in this row, or null if the row is any other kind of chat line. */
        static PrivateMessageRow of(List<Widget> widgets)
        {
            Widget senderWidget = null;
            String sender = null;
            StringBuilder text = new StringBuilder();
            List<Widget> messageWidgets = new ArrayList<>();

            // Left to right, so the sender is found before the message
            List<Widget> ordered = new ArrayList<>(widgets);
            ordered.sort((a, b) -> Integer.compare(a.getRelativeX(), b.getRelativeX()));

            for (Widget widget : ordered)
            {
                String widgetText = widget.getText();
                if (widgetText == null || widgetText.isEmpty())
                {
                    continue;
                }

                Matcher matcher = PRIVATE_MESSAGE_SENDER.matcher(Text.removeTags(widgetText));
                if (senderWidget == null && matcher.matches())
                {
                    senderWidget = widget;
                    sender = matcher.group(1);
                }
                else
                {
                    messageWidgets.add(widget);
                    text.append(widgetText);
                }
            }

            if (senderWidget == null || messageWidgets.isEmpty())
            {
                return null;
            }

            return new PrivateMessageRow(widgets, senderWidget, messageWidgets, sender,
                messageKey(sender, text.toString()));
        }
    }

    /**
     * Creates the sidebar icon.
     */
    private BufferedImage createIcon()
    {
        BufferedImage icon = new BufferedImage(
            16, 16, BufferedImage.TYPE_INT_ARGB
        );

        Graphics2D g = icon.createGraphics();

        g.setRenderingHint(
            RenderingHints.KEY_ANTIALIASING,
            RenderingHints.VALUE_ANTIALIAS_ON
        );

        g.setColor(Color.WHITE);
        g.fillRoundRect(1, 1, 14, 11, 5, 5);

        g.fillPolygon(
            new int[]{4, 4, 1},
            new int[]{9, 14, 11},
            3
        );

        g.setColor(Color.DARK_GRAY);
        g.fillOval(4, 5, 2, 2);
        g.fillOval(7, 5, 2, 2);
        g.fillOval(10, 5, 2, 2);

        g.dispose();

        return icon;
    }

    /**
     * The style chosen in the "Message style" section of the panel. Immutable, so it can be
     * handed from the Swing thread to the client thread safely.
     */
    static final class MessageStyle
    {
        static final MessageStyle DEFAULT = new MessageStyle(ChatFont.GAME_DEFAULT, null, false);

        private final int fontId;
        private final Color color;
        private final boolean overridePrivateMessages;

        MessageStyle(int fontId, Color color, boolean overridePrivateMessages)
        {
            this.fontId = fontId;
            this.color = color;
            this.overridePrivateMessages = overridePrivateMessages;
        }

        int getFontId()
        {
            return fontId;
        }

        /** The custom colour, or null for the game's own colour. */
        Color getColor()
        {
            return color;
        }

        boolean isOverridePrivateMessages()
        {
            return overridePrivateMessages;
        }

        /** Whether this style looks any different from the game's own. */
        boolean changesAnything()
        {
            return fontId != ChatFont.GAME_DEFAULT || color != null;
        }
    }

    /**
     * Sidebar interface: a "Message style" section with the font, size and colour used for sandbox
     * messages (and optionally real PMs), and a collapsed "Test message" section for adding them.
     */
    private static class MessagePanel extends PluginPanel
    {
        private static final Color DEFAULT_MESSAGE_COLOR = Color.WHITE;

        /** The same arrow icons RuneLite's own plugin settings use for collapsible sections. */
        private static final ImageIcon SECTION_EXPAND_ICON;
        private static final ImageIcon SECTION_RETRACT_ICON;

        static
        {
            BufferedImage arrow = ImageUtil.loadImageResource(ChatMessageSandboxPlugin.class, "/util/arrow_right.png");
            SECTION_EXPAND_ICON = new ImageIcon(arrow);
            SECTION_RETRACT_ICON = new ImageIcon(ImageUtil.rotateImage(arrow, Math.PI / 2));
        }

        private final JTextField username = new JTextField("Player");

        private final JComboBox<ChatIcon> icon = new JComboBox<>(ChatIcon.values());

        private final JTextArea message = new JTextArea(4, 18);

        private final JTextField time = new JTextField(
            LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
        );

        private final JComboBox<String> direction =
            new JComboBox<>(new String[]{
                "Incoming",
                "Outgoing"
            });

        private final JComboBox<ChatFont> font = new JComboBox<>(ChatFont.values());

        private final JComboBox<ChatFont.Size> fontSize = new JComboBox<>(ChatFont.Size.values());

        private final JCheckBox showTimestamp = new JCheckBox("Show timestamp");

        private final JCheckBox customColor = new JCheckBox("Custom colour");

        private final ColorJButton colorButton;

        private final JCheckBox overridePrivateMessages =
            new JCheckBox("Apply to all my PMs");

        private final Consumer<MessageStyle> styleListener;

        private Runnable sendAction;

        MessagePanel(ColorPickerManager colorPickerManager, Consumer<MessageStyle> styleListener)
        {
            super(false);
            this.styleListener = styleListener;

            setLayout(new BorderLayout());
            setBackground(ColorScheme.DARK_GRAY_COLOR);

            // Always as wide as the sidebar, so nothing is drawn past its right edge
            JPanel content = new WidthTrackingPanel();
            content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
            content.setBorder(new EmptyBorder(10, 10, 10, 10));
            content.setBackground(ColorScheme.DARK_GRAY_COLOR);

            // ---- Message style (the main section, open) ----
            JPanel styleSection = addSection(content, "Message style",
                "Font, size and colour for sandbox messages", true);

            fontSize.setSelectedItem(ChatFont.Size.MEDIUM);
            font.addActionListener(e -> fireStyleChanged());
            fontSize.addActionListener(e -> fireStyleChanged());
            addField(styleSection, "Font", font);
            addField(styleSection, "Font size", fontSize);

            // Colour: off means the game's normal private message colour
            colorButton = new ColorJButton(ColorUtil.toHexColor(DEFAULT_MESSAGE_COLOR), DEFAULT_MESSAGE_COLOR);
            colorButton.setFocusPainted(false);
            colorButton.setEnabled(false);
            colorButton.addActionListener(e ->
            {
                RuneliteColorPicker picker = colorPickerManager.create(
                    SwingUtilities.getWindowAncestor(this),
                    colorButton.getColor(),
                    "Message colour",
                    true
                );
                picker.setLocationRelativeTo(colorButton);
                picker.setOnColorChange(c ->
                {
                    colorButton.setColor(c);
                    colorButton.setText(ColorUtil.toHexColor(c));
                    fireStyleChanged();
                });
                picker.setVisible(true);
            });

            styleCheckBox(customColor);
            customColor.addActionListener(e ->
            {
                colorButton.setEnabled(customColor.isSelected());
                fireStyleChanged();
            });

            addField(styleSection, "Message colour", customColor);
            addField(styleSection, null, colorButton);

            styleCheckBox(overridePrivateMessages);
            overridePrivateMessages.setToolTipText(
                "Also apply this font, size and colour to your real incoming and outgoing private messages"
            );
            overridePrivateMessages.addActionListener(e -> fireStyleChanged());
            addField(styleSection, "Real private messages", overridePrivateMessages);

            // ---- Test message (closed until opened) ----
            JPanel testSection = addSection(content, "Test message",
                "Add a message to your chat to preview the style", false);

            addField(testSection, "Username", username);
            addField(testSection, "Account icon", icon);

            message.setLineWrap(true);
            message.setWrapStyleWord(true);

            JScrollPane messageScroll = new JScrollPane(message);
            messageScroll.setPreferredSize(new Dimension(200, 90));

            addField(testSection, "Message", messageScroll);
            addField(testSection, "Message direction", direction);

            // Account icons only appear on incoming messages
            direction.addActionListener(e -> icon.setEnabled(!isOutgoing()));
            styleCheckBox(showTimestamp);
            showTimestamp.setSelected(true);
            showTimestamp.setToolTipText(
                "Show a time in front of the message (uses RuneLite's Chat Timestamps plugin)"
            );
            showTimestamp.addActionListener(e -> time.setEnabled(showTimestamp.isSelected()));
            addField(testSection, "Timestamp", showTimestamp);
            addField(testSection, "Time (HH:mm)", time);

            JButton sendButton = new JButton("Add message to chat");
            sendButton.setAlignmentX(Component.LEFT_ALIGNMENT);
            sendButton.addActionListener(e ->
            {
                if (sendAction != null)
                {
                    sendAction.run();
                }
            });
            testSection.add(sendButton);

            // Make the panel scrollable
            JScrollPane scrollPane = new JScrollPane(content);
            scrollPane.setBorder(BorderFactory.createEmptyBorder());
            scrollPane.setHorizontalScrollBarPolicy(
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            );

            add(scrollPane, BorderLayout.CENTER);
        }

        /**
         * Adds a collapsible section styled like the sections in RuneLite's plugin settings:
         * an arrow, an orange title and a thin line underneath. Clicking the arrow or title
         * opens and closes it.
         *
         * @return the panel to put the section's contents in
         */
        private static JPanel addSection(JPanel parent, String title, String description, boolean open)
        {
            JPanel section = new JPanel();
            section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
            section.setBackground(ColorScheme.DARK_GRAY_COLOR);
            section.setAlignmentX(Component.LEFT_ALIGNMENT);

            JPanel header = new JPanel(new BorderLayout());
            header.setBackground(ColorScheme.DARK_GRAY_COLOR);
            header.setAlignmentX(Component.LEFT_ALIGNMENT);
            header.setBorder(new CompoundBorder(
                new MatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR),
                new EmptyBorder(0, 0, 3, 1)
            ));

            JButton toggle = new JButton(open ? SECTION_RETRACT_ICON : SECTION_EXPAND_ICON);
            toggle.setPreferredSize(new Dimension(18, 0));
            toggle.setBorder(new EmptyBorder(0, 0, 0, 5));
            toggle.setToolTipText(open ? "Retract" : "Expand");
            SwingUtil.removeButtonDecorations(toggle);
            header.add(toggle, BorderLayout.WEST);

            JLabel name = new JLabel(title);
            name.setForeground(ColorScheme.BRAND_ORANGE);
            name.setToolTipText("<html>" + title + ":<br>" + description + "</html>");
            header.add(name, BorderLayout.CENTER);

            JPanel contents = new JPanel();
            contents.setLayout(new BoxLayout(contents, BoxLayout.Y_AXIS));
            contents.setBackground(ColorScheme.DARK_GRAY_COLOR);
            contents.setAlignmentX(Component.LEFT_ALIGNMENT);
            contents.setBorder(new EmptyBorder(8, 0, 0, 0));
            contents.setVisible(open);

            Runnable flip = () ->
            {
                boolean nowOpen = !contents.isVisible();
                contents.setVisible(nowOpen);
                toggle.setIcon(nowOpen ? SECTION_RETRACT_ICON : SECTION_EXPAND_ICON);
                toggle.setToolTipText(nowOpen ? "Retract" : "Expand");
                parent.revalidate();
                parent.repaint();
            };
            toggle.addActionListener(e -> flip.run());
            name.addMouseListener(new MouseAdapter()
            {
                @Override
                public void mouseClicked(MouseEvent e)
                {
                    flip.run();
                }
            });

            header.setMaximumSize(new Dimension(Integer.MAX_VALUE, header.getPreferredSize().height));
            section.add(header);
            section.add(contents);

            parent.add(section);
            parent.add(Box.createVerticalStrut(10));
            return contents;
        }

        /**
         * A panel that stretches to the scroll pane's width instead of its own preferred width,
         * so wide children are squeezed to fit rather than cut off.
         */
        private static final class WidthTrackingPanel extends JPanel implements Scrollable
        {
            @Override
            public Dimension getPreferredScrollableViewportSize()
            {
                return getPreferredSize();
            }

            @Override
            public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction)
            {
                return 16;
            }

            @Override
            public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction)
            {
                return visibleRect.height;
            }

            @Override
            public boolean getScrollableTracksViewportWidth()
            {
                return true;
            }

            @Override
            public boolean getScrollableTracksViewportHeight()
            {
                return false;
            }
        }

        private static void styleCheckBox(JCheckBox checkBox)
        {
            checkBox.setForeground(Color.WHITE);
            checkBox.setBackground(ColorScheme.DARK_GRAY_COLOR);
        }

        private static void addField(
            JPanel parent,
            String labelText,
            JComponent component
        )
        {
            if (labelText != null)
            {
                JLabel label = new JLabel(labelText);
                label.setForeground(Color.WHITE);
                label.setAlignmentX(Component.LEFT_ALIGNMENT);

                parent.add(label);
                parent.add(Box.createVerticalStrut(4));
            }

            component.setAlignmentX(Component.LEFT_ALIGNMENT);

            Dimension preferred = component.getPreferredSize();

            component.setMaximumSize(
                new Dimension(Integer.MAX_VALUE, preferred.height)
            );

            parent.add(component);
            parent.add(Box.createVerticalStrut(12));
        }

        private void fireStyleChanged()
        {
            styleListener.accept(getStyle());
        }

        void setSendAction(Runnable action)
        {
            this.sendAction = action;
        }

        String getUsername()
        {
            return username.getText();
        }

        ChatIcon getIcon()
        {
            return (ChatIcon) icon.getSelectedItem();
        }

        String getMessage()
        {
            return message.getText();
        }

        String getTime()
        {
            return time.getText();
        }

        boolean isOutgoing()
        {
            return "Outgoing".equals(direction.getSelectedItem());
        }

        boolean isShowTimestamp()
        {
            return showTimestamp.isSelected();
        }

        /**
         * The current style settings. Call on the Swing thread.
         */
        MessageStyle getStyle()
        {
            ChatFont chatFont = (ChatFont) font.getSelectedItem();
            ChatFont.Size size = (ChatFont.Size) fontSize.getSelectedItem();
            Color color = customColor.isSelected() ? colorButton.getColor() : null;

            return new MessageStyle(
                chatFont.fontId(size),
                color,
                overridePrivateMessages.isSelected()
            );
        }
    }
}
