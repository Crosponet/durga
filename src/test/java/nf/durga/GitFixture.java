package nf.durga;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import nf.durga.storage.GitClient;
import nf.durga.storage.GitTaskStorage;

public class GitFixture {
    public final Path remote;
    public final Path first;
    public final Path second;

    public GitFixture(Path directory) throws IOException {
        Files.createDirectories(directory);
        remote = directory.resolve("remote.git");
        first = directory.resolve("first");
        second = directory.resolve("second");
        GitClient git = new GitClient(directory);
        git.require("init", "--bare", "--initial-branch=main", remote.toString());
        git.require("clone", remote.toString(), first.toString());
        configure(first);
        new GitClient(first).require("commit", "--allow-empty", "-m", "Initialize shared repository");
        new GitClient(first).require("push", "-u", "origin", "main");
        git.require("clone", remote.toString(), second.toString());
        configure(second);
    }

    private void configure(Path path) {
        GitClient git = new GitClient(path);
        git.require("config", "user.name", "Durga Test");
        git.require("config", "user.email", "durga@example.test");
        git.require("config", "commit.gpgsign", "false");
    }

    public GitTaskStorage open(Path path) {
        GitTaskStorage storage = new GitTaskStorage(path);
        storage.init();
        return storage;
    }
}
