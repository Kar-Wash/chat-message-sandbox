
package com.chatmessagesandbox;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import javax.inject.Inject;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.ScrollPaneConstants;
import javax.swing.border.EmptyBorder;

import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MessageNode;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.PluginPanel;

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

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private ClientToolbar clientToolbar;

    private MessagePanel panel;
    private NavigationButton navButton;

    @Override
    protected void startUp()
    {
        // Create the sidebar panel
        panel = new MessagePanel();
        panel.setSendAction(this::sendMessage);

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

        log.info("Chat Message Sandbox stopped!");
    }

    /**
     * Called when the user presses the Send button.
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

        // Parse the timestamp entered in the panel
        final long timestamp;

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

            addMessage(type, username, message, timestamp);
        });
    }

    /**
     * Adds a message using RuneLite's native chat renderer.
     */
    private void addMessage(
        ChatMessageType type,
        String username,
        String message,
        long timestamp
    )
    {
        MessageNode node = client.addChatMessage(
            type,
            username,
            message,
            username
        );

        if (node != null)
        {
            node.setTimestamp(Math.toIntExact(timestamp));
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
     * Sidebar interface for composing chat messages.
     */
    private static class MessagePanel extends PluginPanel
    {
        private final JTextField username = new JTextField("Gnaes");

        private final JTextArea message = new JTextArea(4, 18);

        private final JTextField time = new JTextField(
            LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
        );

        private final JComboBox<String> direction =
            new JComboBox<>(new String[]{
                "Incoming",
                "Outgoing"
            });

        private Runnable sendAction;

        MessagePanel()
        {
            super(false);

            setLayout(new BorderLayout());
            setBackground(ColorScheme.DARK_GRAY_COLOR);

            JPanel content = new JPanel();
            content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
            content.setBorder(new EmptyBorder(12, 10, 12, 10));
            content.setBackground(ColorScheme.DARK_GRAY_COLOR);

            // Username
            addField(content, "Username", username);

            // Message
            message.setLineWrap(true);
            message.setWrapStyleWord(true);

            JScrollPane messageScroll = new JScrollPane(message);
            messageScroll.setPreferredSize(new Dimension(200, 90));

            addField(content, "Message", messageScroll);

            // Direction
            addField(content, "Message direction", direction);

            // Timestamp
            addField(content, "Timestamp (HH:mm)", time);

            // Send button
            JButton sendButton = new JButton("Add message to chat");

            sendButton.setAlignmentX(Component.LEFT_ALIGNMENT);
            sendButton.addActionListener(e ->
            {
                if (sendAction != null)
                {
                    sendAction.run();
                }
            });

            content.add(sendButton);

            // Make the panel scrollable
            JScrollPane scrollPane = new JScrollPane(content);
            scrollPane.setBorder(BorderFactory.createEmptyBorder());
            scrollPane.setHorizontalScrollBarPolicy(
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            );

            add(scrollPane, BorderLayout.CENTER);
        }

        private static void addField(
            JPanel parent,
            String labelText,
            JComponent component
        )
        {
            JLabel label = new JLabel(labelText);
            label.setForeground(Color.WHITE);
            label.setAlignmentX(Component.LEFT_ALIGNMENT);

            parent.add(label);
            parent.add(Box.createVerticalStrut(4));

            component.setAlignmentX(Component.LEFT_ALIGNMENT);

            Dimension preferred = component.getPreferredSize();

            component.setMaximumSize(
                new Dimension(Integer.MAX_VALUE, preferred.height)
            );

            parent.add(component);
            parent.add(Box.createVerticalStrut(12));
        }

        void setSendAction(Runnable action)
        {
            this.sendAction = action;
        }

        String getUsername()
        {
            return username.getText();
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
    }
}
