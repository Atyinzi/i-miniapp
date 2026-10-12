package com.iminiapp.backend.dining.application;

import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.shared.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiningReservationOwnershipTests {
    @Test
    void userCannotReadAnotherUsersReservationByGuessingItsId() {
        OwnershipJdbc jdbc = new OwnershipJdbc();
        DiningReservationService service = new DiningReservationService(jdbc);
        AuthenticatedPrincipal user = new AuthenticatedPrincipal(
                "wechat-user", "user", "USER", List.of("ROLE_USER"),
                "WECHAT", "openid-owner-7");

        assertThrows(BusinessException.class, () -> service.mineDetail(user, 99L));
        assertTrue(jdbc.detailQueryContainedOwnerPredicate);
    }

    private static final class OwnershipJdbc extends JdbcTemplate {
        private boolean detailQueryContainedOwnerPredicate;

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> queryForList(String sql, Class<T> elementType, Object... args) {
            if (sql.contains("SELECT id FROM bf_user WHERE wechat_openid=?")) {
                return (List<T>) List.of(7L);
            }
            throw new AssertionError("Unexpected list query: " + sql);
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            if (sql.contains("FROM bf_dining_reservation r")) {
                detailQueryContainedOwnerPredicate = sql.contains("AND r.user_id=?")
                        && args.length == 2
                        && Long.valueOf(99L).equals(args[0])
                        && Long.valueOf(7L).equals(args[1]);
                return List.of();
            }
            throw new AssertionError("Unexpected detail query: " + sql);
        }
    }
}
