package help.shimmer.app;

import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.widget.EditText;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;

import java.util.ArrayList;

final class ShimmerPromptDelegate implements GeckoSession.PromptDelegate {
    interface FilePromptLauncher {
        void launch(GeckoSession.PromptDelegate.FilePrompt prompt,
                    GeckoResult<GeckoSession.PromptDelegate.PromptResponse> result);
    }

    private final Context context;
    private final FilePromptLauncher filePromptLauncher;

    ShimmerPromptDelegate(Context context, FilePromptLauncher filePromptLauncher) {
        this.context = context;
        this.filePromptLauncher = filePromptLauncher;
    }

    @Override
    public GeckoResult<PromptResponse> onAlertPrompt(
            GeckoSession session, AlertPrompt prompt) {
        GeckoResult<PromptResponse> result = new GeckoResult<>();
        new AlertDialog.Builder(context)
                .setTitle(title(prompt.title))
                .setMessage(prompt.message)
                .setPositiveButton(android.R.string.ok,
                        (dialog, which) -> result.complete(prompt.dismiss()))
                .setOnCancelListener(dialog -> result.complete(prompt.dismiss()))
                .show();
        return result;
    }

    @Override
    public GeckoResult<PromptResponse> onButtonPrompt(
            GeckoSession session, ButtonPrompt prompt) {
        GeckoResult<PromptResponse> result = new GeckoResult<>();
        new AlertDialog.Builder(context)
                .setTitle(title(prompt.title))
                .setMessage(prompt.message)
                .setPositiveButton(android.R.string.ok, (dialog, which) ->
                        result.complete(prompt.confirm(ButtonPrompt.Type.POSITIVE)))
                .setNegativeButton(android.R.string.cancel, (dialog, which) ->
                        result.complete(prompt.confirm(ButtonPrompt.Type.NEGATIVE)))
                .setOnCancelListener(dialog ->
                        result.complete(prompt.confirm(ButtonPrompt.Type.NEGATIVE)))
                .show();
        return result;
    }

    @Override
    public GeckoResult<PromptResponse> onTextPrompt(
            GeckoSession session, TextPrompt prompt) {
        GeckoResult<PromptResponse> result = new GeckoResult<>();
        EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setText(prompt.defaultValue == null ? "" : prompt.defaultValue);
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(context)
                .setTitle(title(prompt.title))
                .setMessage(prompt.message)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) ->
                        result.complete(prompt.confirm(input.getText().toString())))
                .setNegativeButton(android.R.string.cancel, (dialog, which) ->
                        result.complete(prompt.dismiss()))
                .setOnCancelListener(dialog -> result.complete(prompt.dismiss()))
                .show();
        return result;
    }

    @Override
    public GeckoResult<PromptResponse> onChoicePrompt(
            GeckoSession session, ChoicePrompt prompt) {
        GeckoResult<PromptResponse> result = new GeckoResult<>();
        ArrayList<ChoicePrompt.Choice> choices = new ArrayList<>();
        flatten(prompt.choices, choices);
        String[] labels = new String[choices.size()];
        boolean[] checked = new boolean[choices.size()];
        for (int index = 0; index < choices.size(); index++) {
            labels[index] = choices.get(index).label;
            checked[index] = choices.get(index).selected;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle(title(prompt.title))
                .setOnCancelListener(dialog -> result.complete(prompt.dismiss()));
        if (prompt.type == ChoicePrompt.Type.MULTIPLE) {
            builder.setMultiChoiceItems(labels, checked, (dialog, which, isChecked) ->
                            checked[which] = isChecked)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        ArrayList<String> selected = new ArrayList<>();
                        for (int index = 0; index < choices.size(); index++) {
                            if (checked[index] && !choices.get(index).disabled) {
                                selected.add(choices.get(index).id);
                            }
                        }
                        result.complete(prompt.confirm(selected.toArray(new String[0])));
                    })
                    .setNegativeButton(android.R.string.cancel,
                            (dialog, which) -> result.complete(prompt.dismiss()));
        } else {
            builder.setItems(labels, (dialog, which) -> {
                ChoicePrompt.Choice selected = choices.get(which);
                if (selected.disabled) {
                    result.complete(prompt.dismiss());
                } else {
                    result.complete(prompt.confirm(selected));
                }
            });
        }
        builder.show();
        return result;
    }

    @Override
    public GeckoResult<PromptResponse> onFilePrompt(
            GeckoSession session, FilePrompt prompt) {
        GeckoResult<PromptResponse> result = new GeckoResult<>();
        filePromptLauncher.launch(prompt, result);
        return result;
    }

    private static void flatten(ChoicePrompt.Choice[] source,
                                ArrayList<ChoicePrompt.Choice> destination) {
        for (ChoicePrompt.Choice choice : source) {
            if (choice.separator) continue;
            if (choice.items == null) destination.add(choice);
            else flatten(choice.items, destination);
        }
    }

    private static String title(String value) {
        return value == null || value.isBlank() ? "Shimmer" : value;
    }
}
