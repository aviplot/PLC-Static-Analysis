// SYNTAX TEST "source.iec-st" "CODESYS / TwinCAT Structured Text"
FUNCTION_BLOCK FB_Motor EXTENDS FB_Base
// <------------- keyword.other.declaration.iec-st
//                      ^^^^^^^ keyword.other.declaration.iec-st
VAR_INPUT
// <------- storage.type.var.iec-st
    bStart AT %IX0.1 : BOOL := FALSE; // start button
//         ^^ storage.modifier.iec-st
//            ^^^^^^ variable.other.address.iec-st
//                     ^^^^ support.type.elementary.iec-st
//                             ^^^^^ constant.language.iec-st
//                                    ^^^^^^^^^^^^^^^ comment.line.double-slash.iec-st
    tDelay : TIME := T#1s500ms;
//                   ^^^^^^^^^ constant.numeric.duration.iec-st
    nMask : WORD := 16#FF_00;
//                  ^^^^^^^^ constant.numeric.based.iec-st
END_VAR
(* a block (* nested *) comment *) {attribute 'hide'}
// <-------------------------------- comment.block.iec-st
//                                 ^^^^^^^^^^^^^^^^^^ meta.preprocessor.pragma.iec-st
//                                  ^^^^^^^^^ keyword.other.pragma.iec-st
IF bStart AND NOT bStop THEN
// <-- keyword.control.iec-st
//        ^^^ keyword.operator.word.iec-st
//                      ^^^^ keyword.control.iec-st
    fbTimer(IN := TRUE, PT := tDelay);
//  ^^^^^^^ entity.name.function.call.iec-st
//             ^^ keyword.operator.assignment.iec-st
    sText := 'it$'s $N done';
//           ^^^^^^^^^^^^^^^ string.quoted.single.iec-st
//              ^^ constant.character.escape.iec-st
    rValue := 1.5E-3 * x - 1;
//            ^^^^^^ constant.numeric.decimal.iec-st
//                       ^ keyword.operator.arithmetic.iec-st
//                         ^ constant.numeric.decimal.iec-st
END_IF
