select
p.recordData.PATIENT_PRESCRIPTION_INFO.PRESCRIPTION_INFO[].PRESCRIPTION_DATE."@value"
from patient p
where exists p.recordData.PATIENT_PRESCRIPTION_INFO.PRESCRIPTION_INFO[$element.PRESCRIPTION_DATE."@value" >= "2016-9-2T00:00:00" and $element.PRESCRIPTION_DATE."@value" <= "2016-9-2T23:59:59"]
