package рбпо.гришин.лаба5.модель;

import java.time.Instant;
import java.util.UUID;

public class UserSession {
    public UUID id;
    public String username;
    public String deviceId;
    public String accessToken;
    public String refreshToken;
    public Instant accessTokenExpiry;
    public Instant refreshTokenExpiry;
    public SessionStatus status;
}
