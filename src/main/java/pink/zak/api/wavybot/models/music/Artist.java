package pink.zak.api.wavybot.models.music;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.NonNull;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Set;

@Data
@Document
public class Artist {
    @Id
    @NonNull
    private String id;
    @NonNull
    private String name;

    // retrieved from spotify so only present if it is enriched.
    private long lastSpotifyUpdate;
    private Set<SpotifyImage> artistImages;
    private String[] genres;

    @JsonIgnore
    public boolean isRich() {
        return this.artistImages != null;
    }
}
