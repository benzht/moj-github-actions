/*
   Copyright 2020 First Eight BV (The Netherlands)
 

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file / these files except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/
package nl.moj.server.competition.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.moj.server.config.properties.MojServerProperties;
import nl.moj.server.login.SignupForm;
import nl.moj.server.teams.model.Role;
import nl.moj.server.teams.model.Team;
import nl.moj.server.teams.repository.TeamRepository;
import org.apache.commons.io.Charsets;
import org.apache.commons.io.FileUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class CompetitionService {
    private final TeamRepository teamRepository;

    private final PasswordEncoder encoder;

    private final MojServerProperties mojServerProperties;


    public void createNewTeam(SignupForm form) {
        createNewTeam(form, Role.USER);
    }
    public void createNewTeam(SignupForm form, String role) {
        SecurityContext context = SecurityContextHolder.getContext();
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(form.getName(), form
                .getPassword(), Arrays.asList(new SimpleGrantedAuthority(Role.USER)));

        Team team = Team.builder()
                .company(form.getCompany())
                .country(form.getCountry())
                .name(form.getName())
                .password(encoder.encode(form.getPassword()))
                .role(role)
                .uuid(UUID.randomUUID())
                .build();

        context.setAuthentication(authentication);
        saveNewTeam(team);
    }

    private void saveNewTeam(Team team) {
        teamRepository.save(team);

        Path teamdir = mojServerProperties.getDirectories().getBaseDirectory()
                .resolve(mojServerProperties.getDirectories().getTeamDirectory())
                .resolve(team.getName());
        if (!Files.exists(teamdir)) {
            try {
                Files.createDirectory(teamdir);
            } catch (IOException e) {
                log.error("error creating teamdir", e);
            }
        }
    }

    public class UserImporter {
        private List<String> lines;
        private List<Team> teamList = new ArrayList<>();
        private String createRandomPassword() {
            Integer randomNumber = new Random().nextInt(1000);
            Integer randomDay = new Random().nextInt(7);

            return DayOfWeek.of(randomDay).name() +"_"+randomNumber;
        }
        public UserImporter(File file) {
            try {
                lines = FileUtils.readLines(file, StandardCharsets.UTF_8);
                parseLines();
            } catch (Exception ex) {
                log.error(ex.getMessage(), ex);
            }
        }
        public UserImporter(List<String> lines) {
            this.lines = lines;
            parseLines();
        }
        private void parseLines() {
            for (String line: lines) {
                log.info("line " + line);
                String[] parts = line.split("\\|");
                String role = Role.USER;
                if (parts.length>=3 && parts[2].equals(Role.GAME_MASTER)) {
                    role = Role.GAME_MASTER;
                }

                Team team = Team.builder()
                        .company(parts[1])
                        .country("Nederland")
                        .name(parts[0])
                        .password(encoder.encode(createRandomPassword()))
                        .role(role)
                        .uuid(UUID.randomUUID())
                        .build();
                if (team.getName().equalsIgnoreCase("admin")) {
                    continue;
                }
                teamList.add(team);
            }
        }


        private void addOrUpdateAllImportableTeams() {
            for (Team teamInput: this.teamList) {
                Team team = teamRepository.findByName(teamInput.getName());
                if (team==null) {
                    saveNewTeam(teamInput);
                } else {// update just the role and company
                    team.setRole(teamInput.getRole());
                    team.setCompany(teamInput.getCompany());
                    teamRepository.save(team);
                }
            }
        }
    }

    public void importTeams(List<String> lines) {
        UserImporter importer = new UserImporter( lines);
        importer.addOrUpdateAllImportableTeams();
    }
    public void importTeams(File file) {
        UserImporter importer = new UserImporter(file);
        importer.addOrUpdateAllImportableTeams();
    }
}
