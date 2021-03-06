package pink.zak.api.riptide.requesters;

import com.google.common.collect.Sets;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import pink.zak.api.riptide.models.builder.ModelBuilder;
import pink.zak.api.riptide.models.dto.wavy.music.listens.WavyListenDto;
import pink.zak.api.riptide.models.dto.wavy.music.listens.WavyListenPage;
import pink.zak.api.riptide.models.dto.wavy.user.WavyUserDto;
import pink.zak.api.riptide.models.task.Task;
import pink.zak.api.riptide.services.TaskService;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

@Component
public class WavyRequester {
    private final ModelBuilder modelBuilder;
    private final HttpClient httpClient = HttpClient.newBuilder().build();
    private final Executor executor;
    private final TaskService taskService;
    private final Semaphore semaphore = new Semaphore(200);

    private static final String SONG_HISTORY_BASE_URL = "https://wavy.fm/api/internal/legacy/profile/listens/%s?live=false&page=%s&size=100";
    private static final String SONG_HISTORY_SINCE_BASE_URL = "https://wavy.fm/api/internal/legacy/profile/listens/%s?live=false&page=%s&since=%s&size=100";
    private static final String PROFILE_DATA_BASE_URL = "https://wavy.fm/api/internal/legacy/profiles?username=%s";

    @Autowired
    public WavyRequester(ModelBuilder modelBuilder, Executor executor, TaskService taskService) {
        this.modelBuilder = modelBuilder;
        this.executor = executor;
        this.taskService = taskService;
    }

    @NotNull
    @Async
    public CompletableFuture<WavyUserDto> retrieveWavyUser(@NotNull String username) {
        System.out.println("Getting wavy user");
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(String.format(PROFILE_DATA_BASE_URL, username)))
            .build();

        return this.httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(stringResponse -> this.modelBuilder.createWavyUserDto(stringResponse.body()));
    }

    public Task<Set<WavyListenDto>> retrieveAllListens(UUID uuid) {
        Task<Set<WavyListenDto>> task = Task.create();
        task.setFuture(CompletableFuture.supplyAsync(() -> {
            Set<WavyListenDto> listens = Sets.newConcurrentHashSet();
            this.retrieveListenPage(uuid, 0).thenAccept(wavyListenPage -> {
                listens.addAll(wavyListenPage.getTracks());
                int totalTracks = wavyListenPage.getTotalTracks();
                task.setRequiredProgress(totalTracks);
                task.getProgress().addAndGet(wavyListenPage.getTracks().size());
                if (totalTracks > 100) {
                    int requiredPages = (int) Math.ceil(totalTracks / 100.0);
                    Set<CompletableFuture<?>> futures = new HashSet<>();
                    for (int page = 1; page < requiredPages; page++) {
                        int finalPage = page;
                        futures.add(this.retrieveListenPage(uuid, page).thenAccept(wavyPage -> {
                            task.getProgress().addAndGet(wavyPage.getTracks().size());
                            listens.addAll(wavyPage.getTracks());
                        }));
                    }
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[]{})).join();
                }
            }).join();
            return listens;
        }, this.executor));
        this.taskService.addTask(task);
        return task;
    }

    public CompletableFuture<WavyListenPage> retrieveListenPage(UUID uuid, int page) {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(String.format(SONG_HISTORY_BASE_URL, uuid, page)))
            .build();


        return this.doWavyRequest(request, stringResponse -> this.modelBuilder.createListenPage(stringResponse.body()));
    }

    public Task<Set<WavyListenDto>> retrieveListensSince(UUID uuid, long since) {
        Task<Set<WavyListenDto>> task = Task.create();
        task.setFuture(CompletableFuture.supplyAsync(() -> {
            Set<WavyListenDto> listens = Sets.newConcurrentHashSet();
            this.retrieveListensSincePage(uuid, 0, since).thenAccept(wavyListenPage -> {
                listens.addAll(wavyListenPage.getTracks());
                int totalTracks = wavyListenPage.getTotalTracks();
                task.setRequiredProgress(totalTracks);
                task.updateProgress(current -> current + wavyListenPage.getTracks().size());
                if (totalTracks > 100) {
                    int requiredPages = (int) Math.ceil(totalTracks / 100.0);
                    Set<CompletableFuture<?>> futures = new HashSet<>();
                    for (int page = 1; page <= requiredPages; page++) {
                        futures.add(this.retrieveListensSincePage(uuid, page, since).thenAccept(wavyPage -> {
                            task.updateProgress(current -> current + wavyPage.getTracks().size());
                            listens.addAll(wavyPage.getTracks());
                        }));
                    }
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[]{})).join();
                }
            }).join();
            return listens;
        }, this.executor));
        this.taskService.addTask(task);
        return task;
    }

    public CompletableFuture<WavyListenPage> retrieveListensSincePage(UUID uuid, int page, long since) {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(String.format(SONG_HISTORY_SINCE_BASE_URL, uuid, page, since)))
            .build();

        return this.doWavyRequest(request, stringResponse -> this.modelBuilder.createListenPage(stringResponse.body()));
    }

    private <T> CompletableFuture<T> doWavyRequest(HttpRequest httpRequest, Function<HttpResponse<String>, T> responseHandler) {
        return CompletableFuture.runAsync(this.semaphore::acquireUninterruptibly)
            .thenApply(unused -> {
                try {
                    return this.httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
                } catch (IOException | InterruptedException e) {
                    e.printStackTrace();
                    return null;
                }
            })
            .thenApply(stringResponse -> {
                this.semaphore.release();
                return responseHandler.apply(stringResponse);
            });
    }
}
