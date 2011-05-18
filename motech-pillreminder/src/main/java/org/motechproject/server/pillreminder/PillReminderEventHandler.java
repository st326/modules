/**
 * MOTECH PLATFORM OPENSOURCE LICENSE AGREEMENT
 *
 * Copyright (c) 2011 Grameen Foundation USA.  All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * 3. Neither the name of Grameen Foundation USA, nor its respective contributors
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY GRAMEEN FOUNDATION USA AND ITS CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
 * THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED.  IN NO EVENT SHALL GRAMEEN FOUNDATION USA OR ITS CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING
 * IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY
 * OF SUCH DAMAGE.
 */
package org.motechproject.server.pillreminder;

import java.util.Date;

import org.apache.commons.lang.time.DateUtils;
import org.ektorp.DocumentNotFoundException;
import org.motechproject.context.Context;
import org.motechproject.context.EventContext;
import org.motechproject.event.EventRelay;
import org.motechproject.gateway.MotechSchedulerGateway;
import org.motechproject.model.MotechEvent;
import org.motechproject.model.RepeatingSchedulableJob;
import org.motechproject.pillreminder.api.EventKeys;
import org.motechproject.pillreminder.api.PillReminderService;
import org.motechproject.pillreminder.api.dao.PillReminderDao;
import org.motechproject.pillreminder.api.model.PillReminder;
import org.motechproject.pillreminder.api.model.Schedule;
import org.motechproject.server.event.annotations.MotechListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.Assert;

/**
 * Handles Pill Reminder events
 * @author yyonkov
 *
 */
public class PillReminderEventHandler {
	private final Logger logger = LoggerFactory.getLogger(this.getClass());

	enum ProcessType { SCHEDULE, UNSCHEDULE };
	
	private MotechSchedulerGateway schedulerGateway = Context.getInstance().getMotechSchedulerGateway();
	
	@Autowired(required=false)
	private EventRelay eventRelay = EventContext.getInstance().getEventRelay();
	
	@Autowired
	private PillReminderService pillReminderService;
	
	@Autowired
	private PillReminderDao pillReminderDao;
	
	private void processReminder(PillReminder reminder, ProcessType t) {
		MotechEvent event = new MotechEvent(EventKeys.PILLREMINDER_REMINDER_EVENT_SUBJECT);
		event.getParameters().put(EventKeys.PILLREMINDER_ID_KEY, reminder.getId());
		// (un)schedule all reminders
		//TODO clarify if end date is included in the regimen
		Assert.notNull(reminder, "PillReminder must not be null");
		Assert.notNull(reminder.getStartDate(), "PillReminder startDate must not be null");
		Assert.notNull(reminder.getEndDate(), "PillReminder endDate must not be null");
		for(Date d = reminder.getStartDate(); d.before(reminder.getEndDate()); d=DateUtils.addDays(d, +1) ) {
			// (un)schedule for each day
			for( Schedule s : reminder.getSchedules()) {
				// (un)schedule for each time of the day
				Assert.notNull(s, "Schedule must not be null");
				if(t==ProcessType.SCHEDULE) {
					Assert.notNull(s.getStartCallTime(), "Schedule startCallTime must not be null");
					Assert.notNull(s.getEndCallTime(), "Schedule endCallTime must not be null");
					Assert.notNull(s.getRepeatCount(), "Schedule repeatCount must not be null");
					Assert.notNull(s.getRepeatInterval(), "Schedule repeatInterval must not be null");
					event.getParameters().put(EventKeys.SCHEDULE_JOB_ID_KEY, s.getJobId());
					RepeatingSchedulableJob schedulableJob = new RepeatingSchedulableJob(	
							event,
							s.getStartCallTime().getTimeOfDate(d),
							s.getEndCallTime().getTimeOfDate(d), 
							s.getRepeatCount(),
							s.getRepeatInterval() * 1000);
					schedulerGateway.scheduleRepeatingJob(schedulableJob);
				} else {
					schedulerGateway.unscheduleJob(s.getJobId());
				}
			}
		}
	}

	
	/**
	 * Responsible for scheduling and re-scheduling of pill reminders
	 * @param event
	 */
	@MotechListener(subjects={EventKeys.PILLREMINDER_CREATED_SUBJECT, EventKeys.PILLREMINDER_UPDATED_SUBJECT})
	public void schedulePillReminder(MotechEvent event) {
		try {
			PillReminder reminder = pillReminderService.getPillReminder(EventKeys.getReminderID(event));
			processReminder(reminder,ProcessType.SCHEDULE);
		} catch ( DocumentNotFoundException e) {
			logger.error(String.format("PillReminder for ID: %s not found.", EventKeys.getReminderID(event)));
		}
	}
	
	@MotechListener(subjects={EventKeys.PILLREMINDER_DELETED_SUBJECT})
	public void unschedulePillReminder(MotechEvent event) {
		try {
			PillReminder reminder = pillReminderService.getPillReminder(EventKeys.getReminderID(event));
			processReminder(reminder, ProcessType.UNSCHEDULE);
			pillReminderDao.remove(reminder);
		} catch ( DocumentNotFoundException e) {
			logger.error(String.format("PillReminder for ID: %s not found.", EventKeys.getReminderID(event)));
		}
	}

	/**
	 * Responsible for deciding if all pills have been reported
	 * @param event
	 */
	@MotechListener(subjects={EventKeys.PILLREMINDER_REMINDER_EVENT_SUBJECT})
	public void receivePillReminderFromScheduler(MotechEvent event) {
		try {
			if( !pillReminderService.isPillReminderCompleted(EventKeys.getReminderID(event), new Date()) ) {
				eventRelay.sendEventMessage(new MotechEvent(EventKeys.PILLREMINDER_PUBLISH_REMINDER, event.getParameters()));
			}
		} catch ( DocumentNotFoundException e) {
			logger.error(String.format("PillReminder for ID: %s not found.", EventKeys.getReminderID(event)));
		}
	}
}
