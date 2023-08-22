package com.fishbowllabor.server.logic;

import com.fishbowllabor.commons.data.Search;
import com.fishbowllabor.commons.error.ErrorCodeConst;
import com.fishbowllabor.commons.error.ErrorObjectConst;
import com.fishbowllabor.commons.error.FishbowlException;
import com.fishbowllabor.commons.foconst.ClientConst;
import com.fishbowllabor.commons.foconst.OriginConst;
import com.fishbowllabor.commons.foconst.ProjectStatusConst;
import com.fishbowllabor.commons.foconst.TerminalLoginTypeConst;
import com.fishbowllabor.commons.util.Util;
import com.fishbowllabor.fo.global.entity.UserGlobal;
import com.fishbowllabor.server.repository.tenant.*;
import com.fishbowllabor.server.util.RequestUtil;
import com.fishbowllabor.tenant.data.SearchList;
import com.fishbowllabor.tenant.data.timelog.*;
import com.fishbowllabor.tenant.entity.*;
import org.apache.commons.net.util.SubnetUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.web.util.WebUtils;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class TerminalLogic extends BaseLogic {

    @Autowired
    private TimeLogLogic timeLogLogic;
    @Autowired
    private UserLogic userLogic;
    @Autowired
    private UserTenantRepository userTenantRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private UserGlobalLogic userGlobalLogic;
    @Autowired
    private CompanyTenantLogic companyTenantLogic;
    @Autowired
    private TimeLogRepository timeLogRepository;
    @Autowired
    private TerminalRepository terminalRepository;

    public SearchList<Terminal> getTerminalList(Search search) {
        return terminalRepository.findAll(search);
    }

    public Terminal getTerminal(UUID terminalId) {
        Terminal terminal = terminalRepository.findById(terminalId);
        if (terminal == null) {
            throw new FishbowlException(ErrorObjectConst.TERMINAL, ErrorCodeConst.DB_NOT_EXIST);
        }
        return terminal;
    }

    public void checkTerminalAuthorized(HttpServletRequest request, Terminal terminal, UUID employeeCode) {
        if (request.isUserInRole("Admin") || request.isUserInRole("Manager")) {
            return;
        }

        switch (terminal.getRestrictionType()) {
            case IP_RANGE:
                SubnetUtils.SubnetInfo subnet = (new SubnetUtils(terminal.getIpRangeFrom(), terminal.getIpRangeTo())).getInfo();
                boolean inRange = subnet.isInRange(RequestUtil.getClientIp(request));
                if (!inRange) {
                    throw new FishbowlException(ErrorObjectConst.TERMINAL, ErrorCodeConst.TERMINAL_NOT_AUTHORIZED_IP);
                }
                break;
            case DEVICES:
                String deviceToken = terminal.getDeviceToken();
                Cookie requestToken = WebUtils.getCookie(request, "DeviceToken");
                if (requestToken == null || !requestToken.getValue().equals(deviceToken)) {
                    throw new FishbowlException(ErrorObjectConst.TERMINAL, ErrorCodeConst.TERMINAL_NOT_AUTHORIZED);
                }
                break;
            case NONE:
            default:
                break;
        }
    }

    public Terminal saveTerminal(Terminal terminal) {
        resetDefault(terminal);
        Terminal existingTerminal = terminalRepository.findByName(terminal.getName());
        if (existingTerminal != null) {
            throw new FishbowlException(ErrorObjectConst.TERMINAL, ErrorCodeConst.DB_DUPLICATE);
        }
        return terminalRepository.save(terminal, getLoggedInUser());
    }

    public Terminal updateTerminal(UUID terminalId, Terminal terminal) {
        resetDefault(terminal);
        Terminal existingTerminal = terminalRepository.findById(terminalId);

        Terminal existingNameTerminal = terminalRepository.findByName(terminal.getName());
        if (existingNameTerminal != null && !existingNameTerminal.getId().equals(terminalId) && existingNameTerminal.getName().equalsIgnoreCase(terminal.getName())) {
            throw new FishbowlException(ErrorObjectConst.TERMINAL, ErrorCodeConst.DB_DUPLICATE, "name");
        }

        existingTerminal.merge(terminal);

        return terminalRepository.save(existingTerminal, getLoggedInUser());
    }

    private void resetDefault(Terminal terminal) {
        if (terminal.isDefault()) {
            terminalRepository.resetdefault();
        }
    }

    public TimeLog logShiftEntry(TerminalBase terminalBase) {
        ShiftEntry shiftEntry = terminalBase.getShiftEntry();
        shiftEntry.setClient(ClientConst.WEB_TERMINAL.getValue());
        shiftEntry.setOrigin(OriginConst.PUNCH);

        Terminal terminal = getTerminal(terminalBase.getTerminalId());
        UUID userTenantId = shiftEntry.getEmployeeCode();

        UserTenant userTenant = userLogic.getUserTenant(userTenantId, true);
        if (terminal.getLoginType().equals(TerminalLoginTypeConst.DROP_DOWN_PIN)) {
            validatePin(userTenant.getPin(), shiftEntry.getEmployeePin());
        }

        TimeLog timeLog;
        TimeLog currentTimeLog = timeLogRepository.getCurrentTimeLog(shiftEntry.getEmployeeCode());
        if (currentTimeLog == null) {
            timeLog = timeLogLogic.clockIn(shiftEntry);
        } else {
            timeLog = timeLogLogic.clockOut(shiftEntry, true);

            if (terminalBase.getShiftEntry().isCloseProject()) {
                CompanySettings companySettings = companyTenantLogic.getCompanySettings();

                UUID projectId = terminalBase.getShiftEntry().getProjectId() != null ? terminalBase.getShiftEntry().getProjectId() : timeLog.getProjectId() != null ? UUID.fromString(timeLog.getProjectId()) : null;
                if (projectId == null) {
                    throw new FishbowlException(ErrorObjectConst.TIME_LOG, ErrorCodeConst.CUSTOM, "The time log has no " + companySettings.getProjectSingleName() + " to close.");
                }

                Project project = projectRepository.findById(projectId);
                project.setProjectStatus(ProjectStatusConst.CLOSED);
                projectRepository.save(project, getLoggedInUser());
            }
        }

        return timeLog;
    }

    public void logBreak(BreakEntry breakEntry) {
        breakEntry.setOrigin(OriginConst.TIME_CLOCK);
        breakEntry.setClient(ClientConst.WEB_TERMINAL.getValue());
        timeLogLogic.logBreak(breakEntry);
    }

    public void saveNote(ShiftEntry shiftEntry) {
        shiftEntry.setOrigin(OriginConst.TIME_CLOCK);
        TimeLog currentTimeLog = timeLogRepository.getCurrentTimeLog(shiftEntry.getEmployeeCode());
        timeLogLogic.addShiftNote(currentTimeLog.getId(), shiftEntry);
    }

    private boolean validatePin(String userPin, String pinToCompare) {
        if (Util.isEmpty(userPin) || userPin.length() < 4) {
            throw new FishbowlException(ErrorObjectConst.TERMINAL, ErrorCodeConst.CUSTOM, "Pin is invalid.");
        }

        if (!Util.PIN_PATTERN.matcher(userPin).matches()) {
            throw new FishbowlException(ErrorObjectConst.TERMINAL, ErrorCodeConst.CUSTOM, "Pin is invalid.");
        }
        if (!userPin.equals(pinToCompare)) {
            throw new FishbowlException(ErrorObjectConst.TERMINAL, ErrorCodeConst.CUSTOM, "Pin is invalid.");
        }
        return userPin.equals(pinToCompare);
    }

    public Map<String, Object> getLoggedInDetails(TerminalBase terminalBase, HttpServletRequest request) {
        Terminal terminal = getTerminal(terminalBase.getTerminalId());

        UUID employeeCode =  terminalBase.getShiftEntry() != null ? terminalBase.getShiftEntry().getEmployeeCode() : null;
        checkTerminalAuthorized(request, terminal, employeeCode);

        Map<String, Object> loggedInCompany = new HashMap<>();
        Search search = new Search();
        search.setProjectStatus(ProjectStatusConst.OPEN);
        loggedInCompany.put("users", userLogic.getSimpleUserList());
        loggedInCompany.put("terminal", terminal);
        loggedInCompany.put("companyId", terminalBase.getCompanyId());
        loggedInCompany.put("companySettings", companyTenantLogic.getCompanySettings());
        loggedInCompany.put("projects", projectRepository.findAll(search));
        return loggedInCompany;
    }

    public void authorizeTerminal(TerminalAuthorize terminalAuthorize, HttpServletResponse response) {
        Terminal terminal = getTerminal(UUID.fromString(terminalAuthorize.getTerminalId()));
        switch (terminal.getRestrictionType()) {
            case IP_RANGE:
                if (Util.isEmpty(terminalAuthorize.getIpAddressFrom()) || Util.isEmpty(terminalAuthorize.getIpAddressTo())) {
                    throw new FishbowlException(ErrorObjectConst.TERMINAL, ErrorCodeConst.CUSTOM, "To authorize a terminal for an IP address range, both the from and to range fields are required.");
                }

                terminal.setIpRangeFrom(terminalAuthorize.getIpAddressFrom());
                terminal.setIpRangeTo(terminalAuthorize.getIpAddressTo());
                break;
            case DEVICES:
                String deviceToken = UUID.randomUUID().toString();
                terminal.setDeviceToken(deviceToken);
                Cookie deviceTokenCookie = new Cookie("DeviceToken", deviceToken);
                deviceTokenCookie.setMaxAge(60 * 60 * 24 * 365 * 10);
                response.addCookie(deviceTokenCookie);
                break;
            case NONE:
            default:
                break;
        }

        terminalRepository.save(terminal, getLoggedInUser());
    }

    public Map<String, Object> validatePin(ShiftEntry shiftEntry) {
        UUID userTenantId = shiftEntry.getEmployeeCode();
        UserTenant userTenant = userLogic.getUserTenant(userTenantId, true);
        validatePin(userTenant.getPin(), shiftEntry.getEmployeePin());
        return getUserShift(userTenant);
    }

    public Map<String, Object> validateFullLogin(String email, String password) {
        BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
        UserGlobal userGlobal = userGlobalLogic.getUserByEmail(email);
        if (!(passwordEncoder.matches(password, userGlobal.getPassword()))) {
            throw new FishbowlException(ErrorObjectConst.LOGIN, ErrorCodeConst.CUSTOM, "Invalid username or password.");
        }
        return getUserShift(userTenantRepository.findByEmail(email));
    }

    private Map<String, Object> getUserShift(UserTenant userTenant) {
        Map<String, Object> userShift = new HashMap<>();
        userShift.put("shiftLog", timeLogLogic.getCurrentShiftLog(userTenant.getId()));
        userShift.put("userTenant", userTenant);
        return userShift;
    }

    public Map<String, Object> getUserTenant(UUID employeeCode) {
        return getUserShift(userLogic.getUserTenant(employeeCode, true));
    }

    public ShiftLog changeProject(ShiftEntry shiftEntry) {
        shiftEntry.setOrigin(OriginConst.TIME_CLOCK);
        return timeLogLogic.changeProject(shiftEntry);
    }
}
