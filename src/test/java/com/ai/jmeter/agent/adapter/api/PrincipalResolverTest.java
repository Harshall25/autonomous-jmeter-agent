package com.ai.jmeter.agent.adapter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.ai.jmeter.agent.domain.governance.AccessDeniedException;
import com.ai.jmeter.agent.domain.governance.Permission;
import com.ai.jmeter.agent.domain.governance.Principal;
import com.ai.jmeter.agent.domain.governance.Role;
import com.ai.jmeter.agent.domain.governance.TenantId;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Resolving who is calling the control plane")
class PrincipalResolverTest {

    private static final String USER_HEADER = "X-Auth-Subject";
    private static final String TENANT_HEADER = "X-Auth-Tenant";
    private static final String ROLES_HEADER = "X-Auth-Roles";

    @Mock
    private HttpServletRequest request;

    private TrustedHeaderPrincipalResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new TrustedHeaderPrincipalResolver(USER_HEADER, TENANT_HEADER, ROLES_HEADER);
    }

    private void asserted(String user, String tenant, String roles) {
        when(request.getHeader(USER_HEADER)).thenReturn(user);
        when(request.getHeader(TENANT_HEADER)).thenReturn(tenant);
        when(request.getHeader(ROLES_HEADER)).thenReturn(roles);
    }

    @Nested
    @DisplayName("from headers a gateway asserted")
    class FromHeaders {

        @Test
        @DisplayName("takes the identity, tenant and roles the proxy vouched for")
        void readsTheAssertedIdentity() {
            asserted("dana", "acme", "OPERATOR,VIEWER");

            Principal principal = resolver.resolve(request);

            assertThat(principal.id()).isEqualTo("dana");
            assertThat(principal.tenant()).isEqualTo(new TenantId("acme"));
            assertThat(principal.roles()).containsExactlyInAnyOrder(Role.OPERATOR, Role.VIEWER);
        }

        @Test
        @DisplayName("tolerates whatever spacing and case the proxy emits roles in")
        void normalizesRoleNames() {
            asserted("dana", "acme", " operator , , Approver ");

            assertThat(resolver.resolve(request).roles())
                    .containsExactlyInAnyOrder(Role.OPERATOR, Role.APPROVER);
        }

        @Test
        @DisplayName("ignores a role this build does not know rather than locking everyone out")
        void ignoresUnknownRoles() {
            // An identity provider that starts issuing a role a newer version understands must
            // not lock everyone out of an older one — and an unknown role grants nothing anyway.
            asserted("dana", "acme", "OPERATOR,TIME_LORD");

            assertThat(resolver.resolve(request).roles()).containsExactly(Role.OPERATOR);
        }

        @Test
        @DisplayName("gives a caller with no roles header no permissions at all")
        void noRolesHeaderMeansNoPermissions() {
            asserted("dana", "acme", null);

            Principal principal = resolver.resolve(request);

            assertThat(principal.roles()).isEmpty();
            assertThat(principal.can(Permission.VIEW_RUNS)).isFalse();
        }

        @Test
        @DisplayName("refuses a call with no asserted identity rather than defaulting to one")
        void failsClosedWithoutAnIdentity() {
            asserted(null, "acme", "OPERATOR");

            assertThatThrownBy(() -> resolver.resolve(request))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("refuses a call with no asserted tenant")
        void failsClosedWithoutATenant() {
            asserted("dana", "  ", "OPERATOR");

            assertThatThrownBy(() -> resolver.resolve(request))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("refuses a tenant header that is not a tenant id")
        void refusesAnUnusableTenant() {
            // Not a 500, and not a traversal: a header a proxy set wrongly is a denied call.
            asserted("dana", "../globex", "OPERATOR");

            assertThatThrownBy(() -> resolver.resolve(request))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("refuses to exist without every header name configured")
        void requiresExplicitConfiguration() {
            // Header identity is only sound behind a gateway that sets these; making an operator
            // name them is how they state they have one.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new TrustedHeaderPrincipalResolver(
                            null, TENANT_HEADER, ROLES_HEADER))
                    .withMessageContaining("all three header names");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new TrustedHeaderPrincipalResolver(
                            USER_HEADER, " ", ROLES_HEADER))
                    .withMessageContaining("all three header names");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new TrustedHeaderPrincipalResolver(
                            USER_HEADER, TENANT_HEADER, null))
                    .withMessageContaining("all three header names");
        }
    }

    @Test
    @DisplayName("resolves every caller to the local operator when tenancy is off")
    void localModeNeedsNoHeaders() {
        Principal principal = new LocalOperatorPrincipalResolver().resolve(request);

        assertThat(principal.id()).isEqualTo("local-operator");
        assertThat(principal.tenant().isLocal()).isTrue();
        assertThat(principal.can(Permission.VIEW_HEAL_DIFFS)).isTrue();
    }
}
