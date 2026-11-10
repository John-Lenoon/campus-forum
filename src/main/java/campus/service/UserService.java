package campus.service;

import static campus.exception.BusinessException.fail;

import campus.dto.CurrentUser;
import campus.dto.ProfileRequest;
import campus.exception.BusinessException;
import campus.mapper.UserMapper;
import campus.support.AvatarUrls;
import campus.support.TextValidation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
public class UserService {
    private final UserMapper users;
    private final AccountService accounts;
    private final MediaService media;

    public UserService(UserMapper users, AccountService accounts, MediaService media) {
        this.users = users;
        this.accounts = accounts;
        this.media = media;
    }

    @Transactional
    public Map<String, Object> update(CurrentUser user, ProfileRequest input) {
        if (input.nickname() == null && input.avatarResourceId() == null)
            throw fail(400, "INVALID_PROFILE");
        if (input.nickname() != null) {
            TextValidation.text(input.nickname(), 1, 32, "INVALID_NICKNAME");
            users.updateNickname(input.nickname(), user.id());
        }
        if (input.avatarResourceId() != null) media.bindAvatar(user, input.avatarResourceId());
        return accounts.byId(user.id()).view();
    }

    public Map<String, Object> publicUser(String id) {
        var user = users.selectById(id);
        if (user == null) throw fail(404, "NOT_FOUND");
        return Map.of(
                "id",
                user.getId(),
                "nickname",
                user.getNickname(),
                "avatarUrl",
                AvatarUrls.forResource(user.getAvatarResourceId()));
    }

    public Map<String, Object> batch(List<String> ids) {
        if (ids == null || ids.size() > 100) throw fail(400, "INVALID_IDS");
        for (String id : ids) TextValidation.text(id, 1, 100, "INVALID_IDS");
        var items = new ArrayList<Map<String, Object>>();
        var missing = new ArrayList<String>();
        for (String id : new LinkedHashSet<>(ids)) {
            try {
                items.add(publicUser(id));
            } catch (BusinessException e) {
                if (e.status() == 404) missing.add(id);
                else throw e;
            }
        }
        return Map.of("items", items, "missingIds", missing);
    }
}
