package io.github.homchom.recode.mod.commands.arguments.types;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.github.homchom.recode.mod.commands.arguments.StringReaders;
import net.minecraft.commands.SharedSuggestionProvider;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

public class PathArgumentType implements ArgumentType<Path> {
    private final Path root;
    private final boolean greedy;

    private PathArgumentType(Path root, boolean greedy) {
        this.root = root;
        this.greedy = greedy;
    }


    @Deprecated // Hopefully move away from `File` soon.
    public static PathArgumentType folder(File folder, boolean greedy) {
        return folder(folder.toPath(), greedy);
    }

    public static PathArgumentType folder(Path root, boolean greedy) {
        return new PathArgumentType(root, greedy);
    }


    @Override
    public Path parse(StringReader reader) throws CommandSyntaxException {
        String pathInput = greedy ? StringReaders.readRemaining(reader) : reader.readString();

        return null;
    }

    @Override
    public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
        try (Stream<Path> pathStream = Files.list(root)) {
            return SharedSuggestionProvider.suggest(
                    pathStream
                            .map(path -> path.relativize(root))
                            .map(Path::toString)
                            .map(s -> !greedy && s.contains(" ") ? "\"" + s + "\"" : s)
                            .toArray(String[]::new),
                    builder
            );
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
