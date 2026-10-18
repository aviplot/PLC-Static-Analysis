// line comment
(* outer (* nested *) still outer *)
/* C-style /* nested */ comment */
{attribute 'qualified_only'}
{attribute 'TcLinkTo' := '.in := TIIB[Term 1]^Channel 1^Value'}
VAR_GLOBAL
    {attribute 'pytmc' := '
        pv: TEST:VALUE
        io: i
    '}
    gValue : INT; // trailing comment
    {warning 'check this'}
    gOther : INT;
END_VAR
{IF defined (SIMULATION)}
{ELSE}
{END_IF}
{region "Main part"}
{endregion}
