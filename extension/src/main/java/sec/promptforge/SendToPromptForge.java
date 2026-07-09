package sec.promptforge;

import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;

import javax.swing.*;
import java.awt.Component;
import java.util.List;

/**
 * Adds "Send to PromptForge" to Proxy/HTTP-history/Repeater context menus.
 * Hands the selected request/response to the suite tab.
 */
public final class SendToPromptForge implements ContextMenuItemsProvider {

    private final PromptForgeTab tab;

    public SendToPromptForge(PromptForgeTab tab) {
        this.tab = tab;
    }

    @Override
    public List<Component> provideMenuItems(ContextMenuEvent event) {
        List<HttpRequestResponse> selected = resolve(event);
        if (selected.isEmpty()) {
            return List.of();
        }
        // Bulk: one menu item ingests ALL selected history rows at once.
        String label = selected.size() == 1
                ? "Send to PromptForge"
                : "Send " + selected.size() + " requests to PromptForge";
        JMenuItem item = new JMenuItem(label);
        item.addActionListener(e -> tab.ingestSelected(selected));
        return List.of(item);
    }

    /** All explicitly selected rows; fall back to the open message editor. */
    private List<HttpRequestResponse> resolve(ContextMenuEvent event) {
        List<HttpRequestResponse> selected = event.selectedRequestResponses();
        if (selected != null && !selected.isEmpty()) {
            return selected;
        }
        return event.messageEditorRequestResponse()
                .map(m -> List.of(m.requestResponse()))
                .orElse(List.of());
    }
}
