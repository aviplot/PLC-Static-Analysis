FUNCTION_BLOCK FB_Template
VAR
    eState : E_PROGRESS;
    nErrorId : UDINT;
    fbMove : FB_Move;
END_VAR
// Unfinished template code (pattern from a vendor XTS demo): each mistake should give one diagnostic.
IF fbMove.Error THEN
    eState := E_PROGRESS.PROGRESS_ERROR;
    nErrorId :=


ELSIF fbMove.Done THEN
    eState := E_PROGRESS.;

END_IF
CASE eState OF
    E_PROGRESS.PROGRESS_BUSY:
        nErrorId := nErrorId *;
END_CASE
nErrorId := fbMove.;
eState := E_PROGRESS.PROGRESS_INIT;
END_FUNCTION_BLOCK
