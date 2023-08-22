package com.fishbowllabor.server.logic;

import com.fishbowllabor.commons.data.Quantity;
import com.fishbowllabor.commons.data.Search;
import com.fishbowllabor.commons.error.ErrorCodeConst;
import com.fishbowllabor.commons.error.ErrorObjectConst;
import com.fishbowllabor.commons.error.FishbowlException;
import com.fishbowllabor.commons.foconst.NotificationTypeConst;
import com.fishbowllabor.commons.foconst.PtoStatusConst;
import com.fishbowllabor.commons.foconst.PtoTypeConst;
import com.fishbowllabor.commons.util.Util;
import com.fishbowllabor.server.repository.tenant.NotificationRuleRepository;
import com.fishbowllabor.server.repository.tenant.PtoRepository;
import com.fishbowllabor.tenant.data.SearchList;
import com.fishbowllabor.tenant.data.timelog.TimeOff;
import com.fishbowllabor.tenant.entity.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class PtoLogic extends BaseLogic {

    @Autowired
    private UserLogic userLogic;
    @Autowired
    private PtoRepository ptoRepository;
    @Autowired
    private NotificationRuleLogic notificationRuleLogic;
    @Autowired
    private NotificationRuleRepository notificationRuleRepository;


    // V0 Get Method
    public SearchList<TimeOff> ptoRequestSearch(Search search) {
        if (!getLoggedInUser().isAdmin() && getLoggedInUser().isManager()) {
            search.setDepartmentId(UUID.fromString(getLoggedInUser().getDepartmentId()));
        }

        return ptoRepository.findAllTimeOffs(search);
    }

    // V1 Get Method
    public SearchList<TimeOff> getTimeOffs(Search search) {
        if (search == null) {
            search = new Search();
        }

        if (!getLoggedInUser().isAdmin()) {
            if (getLoggedInUser().isManager()) {
                search.setDepartmentId(UUID.fromString(getLoggedInUser().getDepartmentId()));
            } else {
                search.setEmployeeCode(getLoggedInUser().getId());
            }
        }

        return ptoRepository.findAllTimeOffs(search);
    }

    public SearchList<TimeOff> ptoMyRequestSearch(Search search) {
        if (search == null) {
            search = new Search();
        }

        if (search.getEmployeeCode() == null) {
            search.setEmployeeCode(getLoggedInUser().getId());
        }
        SearchList<Pto> ptoList = ptoRepository.findAll(search);

        List<TimeOff> timeOffList = new ArrayList<>();
        for (Pto pto : ptoList.getResultList()) {
            timeOffList.add(new TimeOff(pto, getLoggedInUser()));
        }

        return new SearchList<>(timeOffList, ptoList.getMaxResults());
    }

    public TimeOff getTimeOff(UUID id) {
        Pto pto = ptoRepository.findById(id);
        if (pto == null) {
            throw new FishbowlException(ErrorObjectConst.TIME_OFF, ErrorCodeConst.DB_NOT_EXIST);
        }

        return responseTimeOff(pto);
    }

    private TimeOff responseTimeOff(Pto ptoEntry) {
        TimeOff timeOff = new TimeOff(ptoEntry, getLoggedInUser());

        // more?

        return timeOff;
    }

    // V0 Post End Point method
    // this method takes a TimeOff dto object and builds all needed objects.
    // this method is confusing.
    public void createPtoRequest(TimeOff ptoRequest) {
        if (ptoRequest.getStartDate() == null) {
            throw new FishbowlException("Start Date must be formatted correctly");
        }

        if (!getLoggedInUser().isAdmin() && !getLoggedInUser().isManager() && ptoRequest.getStatus() == PtoStatusConst.APPROVED) {
            throw new FishbowlException(ErrorObjectConst.ACCESS, ErrorCodeConst.ACCESS_DENIED, "You cannot approve Time Off Requests");
        }

        if (ptoRequest.getHours().lessThan(Quantity.ZERO)) {
            throw new FishbowlException(ErrorObjectConst.TIME_OFF, ErrorCodeConst.NEGATIVE_PTO_REQUEST);
        }

        if (ptoRequest.getEmployeeCode() == null) {
            ptoRequest.setEmployeeCode(getLoggedInUser().getId());
        }

        ZonedDateTime endDate = ptoRequest.getEndDate();
        if (endDate == null) {
            endDate = ptoRequest.getStartDate();
        }
        List<Pto> initialDateCheck = ptoRepository.findAllByUserBetweenDates(ptoRequest.getEmployeeCode(), ptoRequest.getStartDate(), endDate.truncatedTo(ChronoUnit.DAYS).plusDays(1).minusSeconds(1));

        List<Pto> dateCheck = new ArrayList<>();
        for (Pto pto : initialDateCheck) {
            if (pto.getDeletedOn() == null && pto.getStatus()!= PtoStatusConst.DENIED)
                dateCheck.add(pto);
        }

        if (!Util.isEmpty(dateCheck) && notDifferentPtoTypes(dateCheck, ptoRequest)) {
            DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
            throw new FishbowlException(ErrorObjectConst.TIME_OFF, ErrorCodeConst.TIMEOFF_ALREADY_REQUESTED,
                    dateFormatter.format(ptoRequest.getStartDate()), dateFormatter.format(endDate));
        }

        long numDays = 1;
        if (ptoRequest.getEndDate() != null) {
            long daysBetween = Duration.between(ptoRequest.getStartDate(), ptoRequest.getEndDate()).toDays();
            if (daysBetween < 0) {
                throw new FishbowlException(ErrorObjectConst.TIME_OFF, ErrorCodeConst.PTO_END_AFTER_START);
            }

            numDays = daysBetween + 1;
        }
        UserTenant userTenant = userLogic.getUserTenant(ptoRequest.getEmployeeCode(), true);

        UUID requestId = UUID.randomUUID();
        for (int i = 0; i < numDays; i++) {
            ZonedDateTime day = ptoRequest.getStartDate().plusDays(i);
            if (!ptoRequest.getIncludeWeekends() && ptoRequest.getEndDate() != null && (DayOfWeek.SATURDAY.equals(day.getDayOfWeek()) || DayOfWeek.SUNDAY.equals(day.getDayOfWeek()))) {
                continue;
            }

            if (ptoRequest.getStatus() == PtoStatusConst.APPROVED) {
                subtractPtoHours(ptoRequest.getType(), ptoRequest.getHours(), userTenant);
            }
            Pto pto = new Pto(userTenant);
            pto.merge(ptoRequest);
            pto.setPtoRequestId(requestId);
            pto.setEventDate(day);
            ptoRepository.save(pto, getLoggedInUser());
        }

        userLogic.saveUserTenant(userTenant);

        sendPtoNotifications(ptoRequest, userTenant);
    }

    private boolean notDifferentPtoTypes(List<Pto> dateCheck, TimeOff ptoRequest) {
        for(Pto pto : dateCheck) {
            if(pto.getType().equals(ptoRequest.getType()) || (!pto.getType().equals(PtoTypeConst.HOLIDAY) && !ptoRequest.getType().equals(PtoTypeConst.HOLIDAY))){
                return true;
            }
        }
        return false;
    }

    // V1 End Point Post method
    public TimeOff createTimeOff(TimeOff timeOffEntry) {

        // check for time off already registered for day
        ZonedDateTime endDate = timeOffEntry.getStartDate();
        List<Pto> initialDateCheck = ptoRepository.findAllByUserBetweenDates(timeOffEntry.getEmployeeCode(), timeOffEntry.getStartDate(), endDate.truncatedTo(ChronoUnit.DAYS).plusDays(1).minusSeconds(1));

        List<Pto> dateCheck = new ArrayList<>();
        for (Pto pto : initialDateCheck) {
            if (pto.getDeletedOn() == null)
                dateCheck.add(pto);
        }

        if (!Util.isEmpty(dateCheck)) {
            DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
            throw new FishbowlException(ErrorObjectConst.TIME_OFF, ErrorCodeConst.TIMEOFF_ALREADY_REQUESTED,
                    dateFormatter.format(timeOffEntry.getStartDate()), dateFormatter.format(endDate));
        }

        if (timeOffEntry.getEmployeeCode() == null) {
            throw new FishbowlException(ErrorObjectConst.TIME_OFF, ErrorCodeConst.MISSING_PARAMETER, "employeeCode");
        }

        // build and save pto
        UserTenant userTenant = getLoggedInUser();

        Pto pto = new Pto(timeOffEntry);
        pto.merge(timeOffEntry);
        pto.setEventDate(timeOffEntry.getStartDate());

        ptoRepository.save(pto, getLoggedInUser());

        sendPtoNotifications(timeOffEntry, userTenant);

        // return time off
        return responseTimeOff(pto);
    }

    private void sendPtoNotifications(TimeOff timeOff, UserTenant userTenant) {
        NotificationRule notificationRule = notificationRuleRepository.findByName(NotificationTypeConst.PTO_REQUESTED.getValue());
        NotificationRule notificationPTONegativeRule = notificationRuleRepository.findByName(NotificationTypeConst.PTO_GOES_NEGATIVE.getValue());
        if (notificationRule.getActive()) {
            String message = notificationRuleLogic.createPtoMessage(timeOff, userTenant);
            notificationRuleLogic.sendNotifications(notificationRule, message);
        }
        if (notificationPTONegativeRule.getActive() && requestExceedsUserBalance(timeOff, userTenant)) {
            String message = notificationRuleLogic.createNegativePtoMessage(timeOff, userTenant);
            notificationRuleLogic.sendNotifications(notificationRule, message);
        }
    }

    public Boolean requestExceedsUserBalance(TimeOff timeOff, UserTenant userTenant) {
        if (timeOff.getType().toString() == "VACATION"){
            if(userTenant.getPtoVacation().compareTo(timeOff.getHours())<0){
                return true;
            }
        }
        if (timeOff.getType().toString() == "SICK"){
            if(userTenant.getPtoSick().compareTo(timeOff.getHours())<0){
                return true;
            }
        }
        if (timeOff.getType().toString() == "HOLIDAY"){
            if(userTenant.getPtoHoliday().compareTo(timeOff.getHours())<0){
                return true;
            }
        }
        return false;
    }

    private void subtractPtoHours(PtoTypeConst ptoType, Quantity hours, UserTenant userTenant) {
        if (ptoType == PtoTypeConst.VACATION) {
            userTenant.setPtoVacation(userTenant.getPtoVacation().subtract(hours));
        } else if (ptoType == PtoTypeConst.SICK) {
            userTenant.setPtoSick(userTenant.getPtoSick().subtract(hours));
        } else if (ptoType == PtoTypeConst.HOLIDAY) {
            userTenant.setPtoHoliday(userTenant.getPtoHoliday().subtract(hours));
        }
    }

    private void addPtoHours(PtoTypeConst ptoType, Quantity hours, UserTenant userTenant) {
        if (ptoType == PtoTypeConst.VACATION) {
            userTenant.setPtoVacation(userTenant.getPtoVacation().add(hours));
        } else if (ptoType == PtoTypeConst.SICK) {
            userTenant.setPtoSick(userTenant.getPtoSick().add(hours));
        } else if (ptoType == PtoTypeConst.HOLIDAY) {
            userTenant.setPtoHoliday(userTenant.getPtoHoliday().add(hours));
        }
    }

    private Pto getPtoToUpdate(UUID ptoId) {
        return ptoRepository.findById(ptoId);
    }

    // V1 Patch End Point method
    public TimeOff updateTimeOff(UUID timeOffId, TimeOff timeOffEntry) {
        Pto pto = getPtoToUpdate(timeOffId);
        if (pto == null) {
            throw new FishbowlException(ErrorObjectConst.TIME_OFF, ErrorCodeConst.DB_NOT_EXIST);
        }

        if(timeOffEntry.getDeletedOn() != pto.getDeletedOn()){
            if (pto.getStatus() == PtoStatusConst.APPROVED && !getLoggedInUser().isAdmin() && !getLoggedInUser().isManager()) {
                throw new FishbowlException(ErrorObjectConst.ACCESS, ErrorCodeConst.ACCESS_DENIED, "Please contact your manager to delete the approved request.");
            } else if (pto.getStatus() != PtoStatusConst.APPROVED && !getLoggedInUser().isAdmin() && !getLoggedInUser().isManager() && !getLoggedInUser().getId().toString().equals(pto.getUserTenantId())) {
                throw new FishbowlException(ErrorObjectConst.ACCESS, ErrorCodeConst.ACCESS_DENIED, "You do not have permission to delete this time off request.");
            }
        }
        if (pto.getDatePosted() != null) {
            throw new FishbowlException(ErrorObjectConst.TIME_OFF, ErrorCodeConst.QUICKBOOKS_POSTED);
        }

        UserTenant userTenant = userLogic.getUserTenant(UUID.fromString(pto.getUserTenantId()), true);

        if (pto.getStatus() == PtoStatusConst.APPROVED && timeOffEntry.getStatus() != PtoStatusConst.APPROVED) {
            addPtoHours(pto.getType(), pto.getHours(), userTenant);
        }

        if (pto.getStatus() != PtoStatusConst.APPROVED && timeOffEntry.getStatus() == PtoStatusConst.APPROVED) {
            subtractPtoHours(pto.getType(), pto.getHours(), userTenant);
        }

        pto.merge(timeOffEntry);

        if (timeOffEntry.getStatus() != null) {
            pto.setStatus(timeOffEntry.getStatus());
        }

        if (timeOffEntry.getDeletedOn() != null) {
            pto.setDeletedOn(timeOffEntry.getDeletedOn());
        }

        if (timeOffEntry.getStartDate() != null) {
            pto.setEventDate(timeOffEntry.getStartDate());
        }

        ptoRepository.save(pto, getLoggedInUser());

        return responseTimeOff(pto);
    }

    // V0 Patch End Point method
    public void updatePtoRequest(UUID requestId, String requestStatus, ZonedDateTime deletedOn) {
        PtoStatusConst ptoStatusConst = PtoStatusConst.getConst(requestStatus);

        List<Pto> ptoRequestGroup = ptoRepository.findByRequestId(requestId);
        if (ptoRequestGroup.isEmpty()) {
            throw new FishbowlException(ErrorObjectConst.TIME_OFF, ErrorCodeConst.DB_NOT_EXIST);
        }
        UserTenant userTenant = userLogic.getUserTenant(UUID.fromString(ptoRequestGroup.get(0).getUserTenantId()), true);

        for (Pto pto : ptoRequestGroup) {
            if (pto == null) {
                throw new FishbowlException(ErrorObjectConst.TIME_OFF, ErrorCodeConst.DB_NOT_EXIST);
            }
            if(deletedOn != pto.getDeletedOn()){
                if (pto.getStatus() == PtoStatusConst.APPROVED && !getLoggedInUser().isAdmin() && !getLoggedInUser().isManager()) {
                    throw new FishbowlException(ErrorObjectConst.ACCESS, ErrorCodeConst.ACCESS_DENIED, "Please contact your manager to delete the approved request.");
                } else if (pto.getStatus() != PtoStatusConst.APPROVED && !getLoggedInUser().isAdmin() && !getLoggedInUser().isManager() && !getLoggedInUser().getId().toString().equals(pto.getUserTenantId())) {
                    throw new FishbowlException(ErrorObjectConst.ACCESS, ErrorCodeConst.ACCESS_DENIED, "You do not have permission to delete this time off request.");
                }
            }
            if (pto.getDatePosted() != null) {
                throw new FishbowlException(ErrorObjectConst.TIME_OFF, ErrorCodeConst.QUICKBOOKS_POSTED);
            }

            if (pto.getStatus() == PtoStatusConst.APPROVED && ptoStatusConst != PtoStatusConst.APPROVED) {
                addPtoHours(pto.getType(), pto.getHours(), userTenant);
            }

            if (pto.getStatus() != PtoStatusConst.APPROVED && ptoStatusConst == PtoStatusConst.APPROVED) {
                subtractPtoHours(pto.getType(), pto.getHours(), userTenant);
            }

            if (pto.getStatus() == PtoStatusConst.APPROVED && ptoStatusConst == PtoStatusConst.APPROVED) {
                addPtoHours(pto.getType(), pto.getHours(), userTenant);
            }

            pto.setStatus(ptoStatusConst);
            pto.setDeletedOn(deletedOn);
            ptoRepository.save(pto, getLoggedInUser());
        }

        userLogic.saveUserTenant(userTenant);
    }

    private TimeOff createPtoRequest(Pto pto) {
        UserTenant userTenant = userLogic.getUserTenant(UUID.fromString(pto.getUserTenantId()), true);
        return new TimeOff(pto, userTenant);
    }
}
