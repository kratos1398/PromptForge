package sec.promptforge;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;

/**
 * Entry point. Burp instantiates this (declared in the manifest) and calls
 * initialize(). We load the bundled taxonomy, build the suite tab, and register
 * the "Send to PromptForge" context-menu item.
 */
public final class PromptForgeExtension implements BurpExtension {

    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName("PromptForge");
        api.logging().logToOutput("PromptForge loading...");

        Taxonomy taxonomy;
        try {
            taxonomy = Taxonomy.load();
        } catch (Exception e) {
            api.logging().logToError("Failed to load bundled taxonomy: " + e);
            return;
        }

        PromptForgeTab tab = new PromptForgeTab(api, taxonomy);
        api.userInterface().registerSuiteTab("PromptForge", tab);
        api.userInterface().registerContextMenuItemsProvider(new SendToPromptForge(tab));

        api.logging().logToOutput("PromptForge ready: "
                + taxonomy.techniques().size() + " techniques, "
                + taxonomy.intents().size() + " intents loaded.");
    }
}
