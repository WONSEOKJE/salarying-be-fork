package com.finalproject.recruit.service;

import com.finalproject.recruit.dto.Response;
import com.finalproject.recruit.dto.member.MemberReqDTO;
import com.finalproject.recruit.dto.member.MemberResDTO;
import com.finalproject.recruit.entity.Member;
import com.finalproject.recruit.jwt.JwtManager;
import com.finalproject.recruit.jwt.JwtProperties;
import com.finalproject.recruit.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ObjectUtils;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class MemberService {

    private final MemberRepository memberRepo;

    private final Response response;

    private final RedisTemplate redisTemplate;

    private final JwtProperties properties;
    private final PasswordEncoder encoder;

    private final JwtManager manager;

    private static final String pattern = "^[A-Za-z[0-9]]{8,16}$"; // 영문, 숫자 8~16자리

    /**
     * 회원가입
     */
    public ResponseEntity<?> signUp(MemberReqDTO.SignUp signUp) {
        if (memberRepo.existsByMemberEmail(signUp.getMemberEmail())) {
            return response.fail("이미 가입된 이메일입니다.", HttpStatus.BAD_REQUEST);
        }

        Member member = Member.builder()
                .memberEmail(signUp.getMemberEmail())
                .password(encoder.encode(signUp.getPassword()))
                .memberPhone(signUp.getMemberPhone())
                .ceoName(signUp.getCeoName())
                .companyName(signUp.getCompanyName())
                .companyNum(signUp.getCompanyNum())
                .memberDelete(false)
                .build();
        memberRepo.save(member);
        return response.success("회원가입에 성공하였습니다.");
    }

    /**
     * 로그인
     **/
    public ResponseEntity<?> login(MemberReqDTO.Login login) {


        Member member = memberRepo
                .findByMemberEmail(login.getMemberEmail())
                .orElseThrow(() -> new RuntimeException());
        if (member == null) {
            return response.fail("이메일을 확인해주세요.");
        }
        if (!checkPassword(login.getPassword(), member.getPassword())) {
            return response.fail("비밀번호를 확인해주세요.");
        }

        MemberResDTO.TokenInfo tokenInfo = new MemberResDTO.TokenInfo(
                manager.generateAccessToken(member, properties.getAccessTokenExpiredTime()),
                manager.generateRefreshToken(member, properties.getRefreshTokenExpiredTime()),
                properties.getRefreshTokenExpiredTime()
        );

        redisTemplate.opsForValue()
                .set("RT : " + login.getMemberEmail(), tokenInfo.getRefreshToken(), tokenInfo.getRefreshTokenExpirationTime(), TimeUnit.MILLISECONDS);
        return response.success(tokenInfo, "로그인에 성공하셨습니다.", HttpStatus.OK);
    }


    /**
     * 로그아웃
     **/
    public ResponseEntity<?> logout(String memberEmail, String accessToken) {
        if (redisTemplate.opsForValue().get("RT : " + memberEmail) != null) {
            redisTemplate.delete("RT : " + memberEmail);
        }

        Long expireTime = manager.getExpiredTime(accessToken);

        redisTemplate.opsForValue()
                .set(accessToken, "logout", expireTime, TimeUnit.MILLISECONDS);

        return response.success("로그아웃 되셨습니다.");
    }


    /**
     * 토큰 기한 연장
     **/
    public ResponseEntity<?> reissue(String accessToken, String memberEmail) {
        if (!manager.isValid(accessToken)) {
            return response.fail("RefreshToken의 정보가 유효하지 않습니다.", HttpStatus.BAD_REQUEST);
        }

        String refreshToken = (String)redisTemplate.opsForValue().get("RT : " + memberEmail);

        if (ObjectUtils.isEmpty(refreshToken)) {
            return response.fail("잘못된 요청입니다.", HttpStatus.BAD_REQUEST);
        }
        Member member = memberRepo
                .findByMemberEmail(memberEmail)
                .orElseThrow(() -> new RuntimeException());

        MemberResDTO.TokenInfo tokenInfo = new MemberResDTO.TokenInfo(
                manager.generateAccessToken(member, properties.getAccessTokenExpiredTime()),
                manager.generateRefreshToken(member, properties.getRefreshTokenExpiredTime()),
                properties.getRefreshTokenExpiredTime()
        );

        redisTemplate.opsForValue()
                .set("RT : " + memberEmail,
                        tokenInfo.getRefreshToken(),
                        tokenInfo.getRefreshTokenExpirationTime(),
                        TimeUnit.MILLISECONDS);

        return response.success(tokenInfo, "갱신하였습니다.", HttpStatus.OK);
    }

    public ResponseEntity<?> existEmail(String email) {
        return memberRepo.existsByMemberEmail(email)?
                response.fail("이미 가입된 이메일입니다."):
                response.success("가입 가능한 이메일입니다.");
    }


    @Transactional
    public ResponseEntity<?> resetPassword(MemberReqDTO.ResetPassword password) {
        System.out.println(password.getNewPassword());
        if (!password.getNewPassword().equals(password.getPasswordCheck())) {
            return response.fail("일치하지 않습니다");
        }
        Member member = memberRepo.findByMemberEmail(password.getMemberEmail()).orElse(null);

        try {
            member.resetPassword(encoder.encode(password.getNewPassword()));
            System.out.println(member.getPassword());
        }
        catch (Exception e) {
            e.printStackTrace();
            return response.fail("실패");
        }
        return response.success();

    }

    /**
     * 기업정보변경
     **/
    @Transactional
    public ResponseEntity<?> updateMemberInfo(String memberEmail, MemberReqDTO.Edit edit) {
        System.out.println(memberEmail);
        Member member = memberRepo
                .findByMemberEmail(memberEmail)
                .orElseThrow(() -> new RuntimeException());

        try {
            edit.setPassword(encoder.encode(edit.getPassword()));
            member.updateMemberInfo(edit);
        }
        catch (Exception e) {
            e.printStackTrace();
            return response.fail("실패");
        }

        return response.success();
    }
    @Transactional
    public ResponseEntity<?> dropMember(String memberEmail) {
        Member member = memberRepo.findByMemberEmail(memberEmail).orElse(null);
        try {
            member.dropMember();
        }
        catch (Exception e) {
            e.printStackTrace();
            return response.fail("실패");
        }
        return response.success();
    }

    private boolean checkPassword(String input, String origin) {
        return encoder.matches(input, origin);
    }
}
