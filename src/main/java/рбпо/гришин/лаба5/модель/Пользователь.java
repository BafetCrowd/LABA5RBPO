package рбпо.гришин.лаба5.модель;

import com.fasterxml.jackson.annotation.JsonIgnore;

public class Пользователь {
    public Long id;
    public String имя;
    public String логин;
    public String роль;
    public Boolean активен;

    @JsonIgnore
    public String хешПароля;
}
