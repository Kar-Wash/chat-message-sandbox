package com.chatmessagesandbox;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class ChatMessageSandboxPluginTest
{
    public static void main(String[] args) throws Exception
    {
        ExternalPluginManager.loadBuiltin(ChatMessageSandboxPlugin.class);
        RuneLite.main(args);
    }
}